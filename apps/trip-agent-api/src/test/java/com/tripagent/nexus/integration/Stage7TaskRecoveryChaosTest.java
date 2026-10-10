package com.tripagent.nexus.integration;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.RefreshMode.BEFORE_EACH_TEST_METHOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AgentContracts.AgentResultEnvelope;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.AgentStatus;
import com.tripagent.nexus.application.planning.PlanningCommandService;
import com.tripagent.nexus.application.service.*;
import com.tripagent.nexus.application.task.RedisRuntimeStateService;
import com.tripagent.nexus.application.task.TaskRecoveryService;
import com.tripagent.nexus.domain.model.DomainEnums.*;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelPlanEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.*;
import com.tripagent.nexus.testsupport.ChaosEvidence;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(
    provider = EMBEDDED,
    type = POSTGRES,
    refresh = BEFORE_EACH_TEST_METHOD)
class Stage7TaskRecoveryChaosTest {
  @Autowired TaskApplicationService tasks;
  @Autowired AgentExecutionService agents;
  @Autowired AgentResultRepository results;
  @Autowired TaskRecordRepository taskRepository;
  @Autowired DocumentRepository documents;
  @Autowired TravelPlanRepository plans;
  @Autowired RedisRuntimeStateService runtime;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired com.tripagent.nexus.application.planning.PlanningCheckpointService checkpoints;

  @Autowired
  org.springframework.beans.factory.ObjectProvider<
          com.tripagent.nexus.application.planning.PlanningWorkflowService>
      workflows;

  @Test
  void duplicateCallbackHasOneDurableAgentResult() throws Exception {
    ChaosEvidence.run(
        "a2a.duplicate",
        "APPLICATION_CALLBACK_REPLAY",
        "AgentExecutionService versioned callback on migrated embedded PostgreSQL",
        record -> {
          var task = tasks.create(TaskType.PLAN_GENERATION, UUID.randomUUID());
          UUID execution = agents.start(task.taskId(), AgentName.RESEARCH);
          UUID event = UUID.randomUUID();
          var result =
              new AgentResultEnvelope<JsonNode>(
                  task.taskId(),
                  AgentName.RESEARCH,
                  AgentStatus.SUCCEEDED,
                  mapper.createObjectNode().put("summary", "Synthetic"),
                  List.of(),
                  List.of(),
                  task.traceId());
          assertThat(agents.completeVersioned(execution, result, 10, 1, event)).isTrue();
          record.injected();
          assertThat(agents.completeVersioned(execution, result, 10, 1, event)).isFalse();
          record.detected();
          assertThat(results.findAll()).hasSize(1);
          assertThat(results.findById(execution).orElseThrow().getResultVersion()).isEqualTo(1);
          record.cleared();
          record.tasks(task.taskId(), task.traceId(), 0, 0);
          record.recovered();
        });
  }

  @Test
  void reconstructedRecoveryServiceKeepsPartialResultAndDoesNotRerunCompletedSteps()
      throws Exception {
    ChaosEvidence.run(
        "a2a.restart",
        "RECOVERY_SERVICE_RECONSTRUCTION",
        "Reconstructed startup recovery service with real PostgreSQL; not a Coordinator process/container restart",
        record -> {
          UUID trip = UUID.randomUUID();
          UUID planId = UUID.randomUUID();
          Instant now = Instant.now();
          jdbc.update(
              "INSERT INTO trip (trip_id,user_id,title,time_zone,status,created_at,updated_at) VALUES (?,?,?,'UTC','READY',now(),now())",
              trip,
              UUID.randomUUID(),
              "Synthetic chaos trip");
          var plan =
              new TravelPlanEntity(
                  planId,
                  trip,
                  planId,
                  null,
                  "stage7",
                  PlanStrategy.BALANCED,
                  1,
                  "Synthetic plan",
                  "",
                  mapper.createArrayNode(),
                  mapper.createObjectNode(),
                  mapper.createArrayNode(),
                  mapper.createArrayNode(),
                  "MOCK",
                  mapper.createObjectNode(),
                  "",
                  false,
                  now);
          plan.complete(
              "Partial evidence",
              mapper.createArrayNode(),
              mapper.createObjectNode(),
              mapper.createArrayNode(),
              mapper.createArrayNode(),
              PlanStatus.PARTIAL,
              "",
              now);
          plans.saveAndFlush(plan);
          var task = tasks.create(TaskType.PLAN_GENERATION, planId);
          tasks.transition(
              task.taskId(), TaskStatus.RUNNING, "RESULTS_PERSISTED", 90, "Results persisted");
          tasks.setResultResources(task.taskId(), List.of(planId));
          jdbc.update(
              "UPDATE task_record SET heartbeat_at = now() - interval '1 hour' WHERE task_id = ?",
              task.taskId());
          var commands = mock(PlanningCommandService.class);
          var outbox = mock(DocumentOutboxService.class);
          var policy = new AsyncGovernanceProperties();
          policy.setRedisEnabled(false);
          policy.setStaleTaskThreshold(Duration.ofMinutes(5));
          record.injected();
          var restarted =
              new TaskRecoveryService(
                  taskRepository,
                  documents,
                  plans,
                  tasks,
                  outbox,
                  commands,
                  runtime,
                  policy,
                  Clock.systemUTC(),
                  checkpoints,
                  workflows);
          restarted.recoverAfterStartup();
          record.detected();
          var recovered = tasks.get(task.taskId());
          assertThat(recovered.status()).isEqualTo(TaskStatus.PARTIAL_SUCCESS);
          assertThat(recovered.degradedComponents()).isNotEmpty();
          assertThat(plans.count()).isEqualTo(1);
          verifyNoInteractions(commands, outbox);
          record.degraded(recovered.message());
          record.cleared();
          restarted.recoverAfterStartup();
          assertThat(tasks.get(task.taskId()).status()).isEqualTo(TaskStatus.PARTIAL_SUCCESS);
          assertThat(plans.count()).isEqualTo(1);
          verifyNoInteractions(commands, outbox);
          record.tasks(task.taskId(), task.traceId(), 0, 0);
          record.measure("redispatchedCompletedSteps", 0);
          record.recovered();
        });
  }
}
