package com.tripagent.nexus.integration;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.*;
import com.tripagent.nexus.agent.AgentContracts.*;
import com.tripagent.nexus.agent.coordinator.CoordinatorAgentModels;
import com.tripagent.nexus.agent.research.ResearchAgentModels;
import com.tripagent.nexus.application.planning.*;
import com.tripagent.nexus.application.service.TaskApplicationService;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.infrastructure.persistence.repository.AgentResultRepository;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = EMBEDDED, type = POSTGRES)
class AgentCheckpointSafetyIntegrationTest {
  @Autowired PlanningCheckpointService planning;
  @Autowired AgentCheckpointService steps;
  @Autowired TaskApplicationService tasks;
  @Autowired AgentResultRepository executions;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  private UUID taskId;
  private String traceId;
  private UUID token;
  private ResearchAgentModels.Input input;
  private AgentDefinition<ResearchAgentModels.Input, ResearchAgentModels.Output> definition;

  @BeforeEach
  void setUp() {
    var task = tasks.create(TaskType.PLAN_GENERATION, UUID.randomUUID());
    taskId = task.taskId();
    traceId = task.traceId();
    tasks.transition(taskId, TaskStatus.RUNNING, "AGENTS_RUNNING", 40, "Synthetic running task");
    var context = ContextEnvelope.empty(1, 8000);
    input =
        new ResearchAgentModels.Input(
            UUID.randomUUID(), context, List.of("Beijing"), List.of("area"));
    token =
        planning
            .create(
                taskId,
                "input-hash",
                "tenant-a",
                new CoordinatorAgentModels.Input(taskId, context, "Synthetic"),
                new PlanningKnowledgeService.KnowledgeResult(List.of(), false, List.of()))
            .input()
            .executionToken();
    definition = definition("research-v1");
  }

  @Test
  void interruptedStepRetriesOnceAndStaleCallbackIsFenced() {
    var first = steps.begin(taskId, token, definition, input, "tenant-a");
    UUID stale = token;
    token = planning.resume(taskId, "input-hash", "tenant-a").input().executionToken();
    var second = steps.begin(taskId, token, definition, input, "tenant-a");
    assertThat(second.executionId()).isNotEqualTo(first.executionId());
    assertThat(executions.findById(first.executionId()).orElseThrow().getStatus())
        .isEqualTo(TaskStatus.FAILED);
    assertThatThrownBy(
            () ->
                steps.complete(
                    taskId,
                    stale,
                    first.executionId(),
                    definition,
                    result(AgentStatus.SUCCEEDED),
                    1))
        .hasMessage("PLANNING_CHECKPOINT_INVALID");
    steps.complete(
        taskId, token, second.executionId(), definition, result(AgentStatus.DEGRADED), 1);
    var cached = steps.begin(taskId, token, definition, input, "tenant-a");
    assertThat(cached.result().status()).isEqualTo(AgentStatus.DEGRADED);
    assertThat(cached.result().warnings()).hasSize(1);
    assertThat(cached.executionId()).isEqualTo(second.executionId());
  }

  @Test
  void repeatedCompletionCannotReplaceCommittedStep() {
    var claim = steps.begin(taskId, token, definition, input, "tenant-a");
    steps.complete(taskId, token, claim.executionId(), definition, result(AgentStatus.DEGRADED), 1);
    assertThatThrownBy(
            () ->
                steps.complete(
                    taskId,
                    token,
                    claim.executionId(),
                    definition,
                    result(AgentStatus.SUCCEEDED),
                    1))
        .hasMessage("PLANNING_CHECKPOINT_INVALID");
    assertThat(steps.begin(taskId, token, definition, input, "tenant-a").result().status())
        .isEqualTo(AgentStatus.DEGRADED);
  }

  @Test
  void sameRunCannotClaimAlreadyRunningStep() {
    steps.begin(taskId, token, definition, input, "tenant-a");
    assertThatThrownBy(() -> steps.begin(taskId, token, definition, input, "tenant-a"))
        .hasMessage("PLANNING_CHECKPOINT_INVALID");
    assertThat(executions.findByTaskIdOrderByExecutionSequenceAsc(taskId)).hasSize(1);
  }

  @Test
  void taskLockExcludesASecondWorkerAndReleases() throws Exception {
    var first = planning.tryLock(taskId).orElseThrow();
    try {
      assertThat(planning.tryLock(taskId)).isEmpty();
    } finally {
      first.close();
    }
    try (var next = planning.tryLock(taskId).orElseThrow()) {
      next.requireValid();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"tenant", "input", "prompt"})
  void changedStepContractIsRejected(String changed) {
    var claim = steps.begin(taskId, token, definition, input, "tenant-a");
    steps.complete(
        taskId, token, claim.executionId(), definition, result(AgentStatus.SUCCEEDED), 1);
    var changedInput =
        changed.equals("input")
            ? new ResearchAgentModels.Input(
                input.taskId(), input.context(), List.of("Shanghai"), input.researchQuestions())
            : input;
    assertThatThrownBy(
            () ->
                steps.begin(
                    taskId,
                    token,
                    changed.equals("prompt") ? definition("research-v2") : definition,
                    changedInput,
                    changed.equals("tenant") ? "tenant-b" : "tenant-a"))
        .hasMessage("PLANNING_CHECKPOINT_INVALID");
    assertThat(executions.findByTaskIdOrderByExecutionSequenceAsc(taskId)).hasSize(1);
  }

  @Test
  void workflowInputAndTenantChangesInvalidateSnapshot() {
    assertThatThrownBy(() -> planning.resume(taskId, "other-input", "tenant-a"))
        .hasMessage("PLANNING_CHECKPOINT_INVALID");
    assertThatThrownBy(() -> planning.resume(taskId, "input-hash", "tenant-b"))
        .hasMessage("PLANNING_CHECKPOINT_INVALID");
  }

  @ParameterizedTest
  @ValueSource(strings = {"FAILED", "CANCELLED", "SUCCEEDED", "expired", "writing"})
  void cancelledTerminalAndExpiredTasksCannotStartAgent(String state) {
    if (state.equals("expired")) {
      jdbc.update(
          "UPDATE planning_checkpoint SET created_at=now()-interval '2 day',expires_at=now()-interval '1 day' WHERE task_id=?",
          taskId);
    } else if (state.equals("writing")) {
      jdbc.update(
          "UPDATE task_record SET stage='CANDIDATES_ASSEMBLED',progress=65 WHERE task_id=?",
          taskId);
    } else {
      jdbc.update(
          "UPDATE task_record SET status=?,cancel_requested=? WHERE task_id=?",
          state,
          state.equals("CANCELLED"),
          taskId);
    }
    assertThatThrownBy(() -> steps.begin(taskId, token, definition, input, "tenant-a"))
        .hasMessage("PLANNING_CHECKPOINT_INVALID");
    assertThat(executions.findByTaskIdOrderByExecutionSequenceAsc(taskId)).isEmpty();
  }

  @Test
  void expiredCheckpointCleanupRemovesPrivateResults() {
    steps.begin(taskId, token, definition, input, "tenant-a");
    jdbc.update(
        "UPDATE planning_checkpoint SET created_at=now()-interval '2 day',expires_at=now()-interval '1 day' WHERE task_id=?",
        taskId);
    planning.purgeExpired();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agent_step_checkpoint WHERE task_id=?",
                Integer.class,
                taskId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM planning_checkpoint WHERE task_id=?", Integer.class, taskId))
        .isZero();
    assertThat(executions.findByTaskIdOrderByExecutionSequenceAsc(taskId)).hasSize(1);
  }

  @Test
  void privacySetSerializationIsStableAcrossInsertionOrders() throws Exception {
    var first =
        new PrivacyLabel(
            "/parsedBookings/0",
            PrivacyClassification.PERSONAL,
            new java.util.LinkedHashSet<>(
                List.of(AgentName.RESEARCH, AgentName.REQUIREMENT, AgentName.BUDGET)));
    var second =
        new PrivacyLabel(
            "/parsedBookings/0",
            PrivacyClassification.PERSONAL,
            new java.util.LinkedHashSet<>(
                List.of(AgentName.BUDGET, AgentName.REQUIREMENT, AgentName.RESEARCH)));
    assertThat(mapper.writeValueAsString(first)).isEqualTo(mapper.writeValueAsString(second));
    assertThat(first.allowedAgents())
        .containsExactly(AgentName.REQUIREMENT, AgentName.RESEARCH, AgentName.BUDGET);
    assertThat(planning.fingerprint(first))
        .isEqualTo(
            planning.fingerprint(
                mapper.readValue(mapper.writeValueAsString(second), PrivacyLabel.class)));
  }

  @Test
  void fingerprintPreservesNumericalValueAcrossJsonbRepresentations() throws Exception {
    assertThat(planning.fingerprint(mapper.readTree("{\"budget\":3000.0000,\"score\":1.0}")))
        .isEqualTo(planning.fingerprint(mapper.readTree("{\"score\":1,\"budget\":3e3}")));
    assertThat(planning.fingerprint(mapper.readTree("{\"budget\":3000}")))
        .isNotEqualTo(planning.fingerprint(mapper.readTree("{\"budget\":3001}")));
  }

  @Test
  void advisoryLocksLeaveCapacityForBusinessTransactions() throws Exception {
    try (var metadataConnection = jdbc.getDataSource().getConnection();
        var pool = new com.zaxxer.hikari.HikariDataSource()) {
      pool.setJdbcUrl(metadataConnection.getMetaData().getURL());
      pool.setUsername(metadataConnection.getMetaData().getUserName());
      pool.setMaximumPoolSize(2);
      pool.setConnectionTimeout(500);
      var template = new JdbcTemplate(pool);
      var scoped =
          new PlanningCheckpointService(
              pool,
              template,
              mapper,
              new com.tripagent.nexus.infrastructure.config.PlanningProperties(),
              java.time.Clock.systemUTC());
      try (var lock = scoped.tryLock(taskId).orElseThrow()) {
        lock.requireValid();
        assertThatThrownBy(() -> scoped.tryLock(UUID.randomUUID()))
            .hasMessage("PLANNING_CAPACITY_BUSY");
        assertThat(template.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
      }
      assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
  }

  @Test
  void parallelDuplicateClaimCreatesOnlyOneExecution() throws Exception {
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Boolean> operation =
          () -> {
            start.await();
            try {
              steps.begin(taskId, token, definition, input, "tenant-a");
              return true;
            } catch (com.tripagent.nexus.integration.messaging.AsyncOperationException rejected) {
              assertThat(rejected.code()).isEqualTo("PLANNING_CHECKPOINT_INVALID");
              return false;
            }
          };
      var first = workers.submit(operation);
      var second = workers.submit(operation);
      start.countDown();
      int acquired =
          (first.get(5, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)
              + (second.get(5, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0);
      assertThat(acquired).isEqualTo(1);
      assertThat(executions.findByTaskIdOrderByExecutionSequenceAsc(taskId)).hasSize(1);
    }
  }

  @Test
  void damagedCachedOutputIsNotAccepted() {
    var claim = steps.begin(taskId, token, definition, input, "tenant-a");
    steps.complete(
        taskId, token, claim.executionId(), definition, result(AgentStatus.SUCCEEDED), 1);
    jdbc.update(
        "UPDATE agent_step_checkpoint SET result_envelope=jsonb_set(result_envelope,'{result}','{}'::jsonb) WHERE task_id=?",
        taskId);
    assertThatThrownBy(() -> steps.begin(taskId, token, definition, input, "tenant-a"))
        .isInstanceOf(AgentSchemaValidator.AgentValidationException.class);
  }

  @Test
  void invalidOutputRollsBackCheckpointAndProjectionTogether() {
    var claim = steps.begin(taskId, token, definition, input, "tenant-a");
    var invalid =
        new AgentResultEnvelope<com.fasterxml.jackson.databind.JsonNode>(
            UUID.randomUUID(),
            AgentName.RESEARCH,
            AgentStatus.SUCCEEDED,
            mapper.createObjectNode(),
            List.<AgentEvidence>of(),
            List.<AgentWarning>of(),
            traceId);
    assertThatThrownBy(
            () -> steps.complete(taskId, token, claim.executionId(), definition, invalid, 1))
        .isInstanceOf(AgentSchemaValidator.AgentValidationException.class);
    assertThat(executions.findById(claim.executionId()).orElseThrow().getStatus())
        .isEqualTo(TaskStatus.RUNNING);
    assertThat(
            jdbc.queryForObject(
                "SELECT result_envelope IS NULL FROM agent_step_checkpoint WHERE task_id=?",
                Boolean.class,
                taskId))
        .isTrue();
  }

  private AgentResultEnvelope<com.fasterxml.jackson.databind.JsonNode> result(AgentStatus status) {
    return new AgentResultEnvelope<>(
        UUID.randomUUID(),
        AgentName.RESEARCH,
        status,
        mapper.valueToTree(
            new ResearchAgentModels.Output(
                "Synthetic", List.of("Area"), status == AgentStatus.DEGRADED)),
        List.of(),
        status == AgentStatus.DEGRADED
            ? List.of(new AgentWarning("SYNTHETIC_PARTIAL", "Limited evidence."))
            : List.of(),
        traceId);
  }

  private AgentDefinition<ResearchAgentModels.Input, ResearchAgentModels.Output> definition(
      String prompt) {
    return new AgentDefinition<>(
        AgentName.RESEARCH,
        AgentCardFactory.create(AgentName.RESEARCH, "Synthetic", Set.of(), "1.0.0"),
        ResearchAgentModels.Input.class,
        ResearchAgentModels.Output.class,
        Set.of(),
        new AgentPolicy(
            Duration.ofSeconds(1),
            new RetryPolicy(1, Duration.ZERO, Duration.ZERO),
            Set.of(),
            true,
            prompt));
  }
}
