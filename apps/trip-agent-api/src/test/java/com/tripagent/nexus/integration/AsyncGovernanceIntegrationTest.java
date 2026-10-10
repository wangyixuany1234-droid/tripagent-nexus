package com.tripagent.nexus.integration;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.RefreshMode.BEFORE_EACH_TEST_METHOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AgentContracts.AgentResultEnvelope;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.AgentStatus;
import com.tripagent.nexus.application.service.AgentExecutionService;
import com.tripagent.nexus.application.service.DeadLetterService;
import com.tripagent.nexus.application.service.EventInboxService;
import com.tripagent.nexus.application.service.EventInboxService.InboxClaim;
import com.tripagent.nexus.application.service.EventOutboxService;
import com.tripagent.nexus.application.service.TaskApplicationService;
import com.tripagent.nexus.application.task.RedisRuntimeStateService;
import com.tripagent.nexus.application.task.TaskEventStream;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.domain.model.DomainEnums.InboxStatus;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStatus;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStrategy;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.domain.model.TaskEvent;
import com.tripagent.nexus.infrastructure.persistence.entity.TaskEventEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelPlanEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.AgentResultRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.DeadLetterEventRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.EventInboxRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.OutboxEventRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TaskEventRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelPlanRepository;
import com.tripagent.nexus.integration.messaging.AsyncOperationException;
import com.tripagent.nexus.integration.messaging.EventCausationContext;
import com.tripagent.nexus.integration.messaging.EventEnvelope;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(
    provider = EMBEDDED,
    type = POSTGRES,
    refresh = BEFORE_EACH_TEST_METHOD)
class AsyncGovernanceIntegrationTest {

  private static final String OPERATIONS_TOKEN = "test-operations-token-000000000001";

  @Autowired private EventInboxService inboxService;
  @Autowired private EventInboxRepository inboxRepository;
  @Autowired private EventOutboxService outboxService;
  @Autowired private TaskApplicationService taskService;
  @Autowired private TaskEventStream eventStream;
  @Autowired private EventCausationContext causationContext;
  @Autowired private AgentExecutionService agentExecutionService;
  @Autowired private AgentResultRepository agentResultRepository;
  @Autowired private DeadLetterService deadLetterService;
  @Autowired private DeadLetterEventRepository deadLetterRepository;
  @Autowired private OutboxEventRepository outboxRepository;
  @Autowired private TaskEventRepository taskEventRepository;
  @Autowired private TravelPlanRepository planRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MockMvc mockMvc;
  @MockitoSpyBean private RedisRuntimeStateService runtimeState;

  @Test
  void consumerDeduplicationAndSseResumeUseDurableState() {
    UUID taskId = UUID.randomUUID();
    EventEnvelope event = event(taskId, "trip.plan.requested");

    assertThat(inboxService.claim("test-consumer", event)).isEqualTo(InboxClaim.ACQUIRED);
    inboxService.fail("test-consumer", event.eventId(), "TEMPORARY_FAILURE", true);
    assertThat(inboxService.claim("test-consumer", event)).isEqualTo(InboxClaim.ACQUIRED);
    inboxService.complete("test-consumer", event.eventId());
    assertThat(inboxService.claim("test-consumer", event)).isEqualTo(InboxClaim.DUPLICATE);
    assertThat(inboxRepository.count()).isEqualTo(1);
    assertThat(
            inboxRepository
                .findByConsumerNameAndEventId("test-consumer", event.eventId())
                .orElseThrow()
                .getStatus())
        .isEqualTo(InboxStatus.COMPLETED);
    assertThat(
            inboxRepository
                .findByConsumerNameAndEventId("test-consumer", event.eventId())
                .orElseThrow()
                .getAttempts())
        .isEqualTo(2);

    UUID resourceId = UUID.randomUUID();
    UUID persistedTaskId = taskService.create(TaskType.PLAN_GENERATION, resourceId).taskId();
    taskService.transition(persistedTaskId, TaskStatus.RUNNING, "STEP_ONE", 10, "First step");
    taskService.transition(persistedTaskId, TaskStatus.RUNNING, "STEP_TWO", 20, "Second step");
    List<TaskEvent> all = eventStream.replay(persistedTaskId, null);
    UUID firstStepId =
        all.stream()
            .filter(item -> "STEP_ONE".equals(item.stage()))
            .findFirst()
            .orElseThrow()
            .eventId();

    assertThat(eventStream.replay(persistedTaskId, firstStepId))
        .extracting(TaskEvent::stage)
        .containsExactly("STEP_TWO");
  }

  @Test
  void sseReplayUsesDurableSequenceWhenEventTimestampsArriveOutOfOrder() {
    UUID taskId = taskService.create(TaskType.PLAN_GENERATION, UUID.randomUUID()).taskId();
    Instant laterTimestamp = Instant.parse("2026-10-01T10:05:00Z");
    Instant earlierTimestamp = Instant.parse("2026-10-01T10:00:00Z");
    taskEventRepository.saveAndFlush(
        new TaskEventEntity(
            UUID.randomUUID(), taskId, "SEQUENCE_FIRST", 10, "Persisted first", laterTimestamp));
    taskEventRepository.saveAndFlush(
        new TaskEventEntity(
            UUID.randomUUID(),
            taskId,
            "SEQUENCE_SECOND",
            20,
            "Persisted second",
            earlierTimestamp));

    List<TaskEvent> replayed =
        eventStream.replay(taskId, null).stream()
            .filter(event -> event.stage().startsWith("SEQUENCE_"))
            .toList();

    assertThat(replayed)
        .extracting(TaskEvent::stage)
        .containsExactly("SEQUENCE_FIRST", "SEQUENCE_SECOND");
    assertThat(replayed.getFirst().timestamp()).isAfter(replayed.get(1).timestamp());
  }

  @Test
  void protectedDeadLetterReplayCreatesANewTaskAndKeepsTheFailedTaskImmutable() throws Exception {
    UUID resourceId = UUID.randomUUID();
    UUID failedTaskId = taskService.create(TaskType.PLAN_GENERATION, resourceId).taskId();
    taskService.transition(failedTaskId, TaskStatus.RUNNING, "AGENTS_RUNNING", 40, "Running");
    EventEnvelope original = event(failedTaskId, "trip.plan.requested");
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>(
            "trip.plan.requested",
            0,
            10L,
            failedTaskId.toString(),
            objectMapper.writeValueAsString(original));
    deadLetterService.capture(
        record,
        new AsyncOperationException(
            "AGENT_SERVICE_UNAVAILABLE",
            true,
            "The Agent service remained unavailable after retries."));
    UUID deadLetterId =
        deadLetterRepository
            .findByOriginalEventId(original.eventId())
            .orElseThrow()
            .getDeadLetterId();

    mockMvc
        .perform(get("/api/v1/operations/dead-letters"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("OPERATIONS_UNAUTHORIZED"));
    mockMvc
        .perform(
            get("/api/v1/operations/dead-letters")
                .header("X-Operations-Token", "invalid-token-value-000000000000"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("OPERATIONS_UNAUTHORIZED"));

    JsonNode replayed =
        objectMapper.readTree(
            mockMvc
                .perform(
                    post("/api/v1/operations/dead-letters/{deadLetterId}/replay", deadLetterId)
                        .header("X-Operations-Token", OPERATIONS_TOKEN)
                        .header("Idempotency-Key", "dead-letter-replay-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Parser service recovered\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REPLAYED"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray());

    UUID replayTaskId = UUID.fromString(replayed.path("replayTaskId").asText());
    assertThat(replayTaskId).isNotEqualTo(failedTaskId);
    assertThat(taskService.get(failedTaskId).status()).isEqualTo(TaskStatus.FAILED);
    assertThat(taskService.get(replayTaskId).status()).isEqualTo(TaskStatus.QUEUED);
    assertThat(taskService.get(replayTaskId).retryOfTaskId()).isEqualTo(failedTaskId);
    assertThat(outboxRepository.count()).isEqualTo(2);
  }

  @Test
  void malformedKafkaRecordProducesAContractCompleteDeadLetterEnvelope() {
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>("trip.plan.requested", 2, 17L, "invalid-key", "{not-json");

    deadLetterService.capture(record, new IllegalArgumentException("invalid event"));

    assertThat(deadLetterRepository.count()).isEqualTo(1);
    assertThat(outboxRepository.count()).isEqualTo(1);
    EventEnvelope deadLetter =
        objectMapper.convertValue(
            outboxRepository.findAll().getFirst().getPayload(), EventEnvelope.class);
    UUID originalEventId = UUID.fromString(deadLetter.payload().path("originalEventId").asText());
    assertThat(deadLetter.eventType()).isEqualTo("dead-letter");
    assertThat(deadLetter.tripId()).isEqualTo(originalEventId);
    assertThat(deadLetter.taskId()).isEqualTo(originalEventId);
  }

  @Test
  void outboxUsesTopicAndIdempotencyKeyAsTheDurableDeduplicationBoundary() {
    UUID taskId = UUID.randomUUID();
    EventEnvelope first = event(taskId, "trip.plan.requested", "stage6-outbox-dedup-0001");
    EventEnvelope repeated =
        new EventEnvelope(
            UUID.randomUUID(),
            first.eventType(),
            first.schemaVersion(),
            first.occurredAt(),
            first.correlationId(),
            first.causationId(),
            first.traceId(),
            first.tripId(),
            first.taskId(),
            first.idempotencyKey(),
            first.payload());

    UUID firstId = outboxService.enqueue("trip.plan.requested", "plan-key", taskId, first);
    UUID repeatedId = outboxService.enqueue("trip.plan.requested", "plan-key", taskId, repeated);

    assertThat(repeatedId).isEqualTo(firstId);
    assertThat(outboxRepository.count()).isEqualTo(1);
  }

  @Test
  void taskStateChangesPersistTraceAndKafkaCausation() {
    UUID taskId = taskService.create(TaskType.PLAN_GENERATION, UUID.randomUUID()).taskId();
    EventEnvelope source = event(taskId, "trip.plan.requested");

    causationContext.run(
        source,
        () ->
            taskService.transition(
                taskId, TaskStatus.RUNNING, "STAGE6_RUNNING", 25, "Synthetic stage six work"));

    TaskEventEntity persisted =
        taskEventRepository.findByTaskIdOrderBySequenceAsc(taskId).stream()
            .filter(item -> "STAGE6_RUNNING".equals(item.getStage()))
            .findFirst()
            .orElseThrow();
    assertThat(persisted.getTraceId()).isEqualTo(source.traceId());
    assertThat(persisted.getSourceEventId()).isEqualTo(source.eventId());
    assertThat(persisted.getSourceEventType()).isEqualTo(source.eventType());
  }

  @Test
  void duplicateAndOlderAgentResultsCannotOverwriteTheNewestVersion() {
    UUID taskId = taskService.create(TaskType.PLAN_GENERATION, UUID.randomUUID()).taskId();
    UUID executionId = agentExecutionService.start(taskId, AgentName.RESEARCH);
    UUID versionTwoEvent = UUID.randomUUID();
    AgentResultEnvelope<JsonNode> versionTwo = agentResult(taskId, "new-result");
    AgentResultEnvelope<JsonNode> staleVersion = agentResult(taskId, "old-result");

    assertThat(
            agentExecutionService.completeVersioned(
                executionId, versionTwo, 25, 2, versionTwoEvent))
        .isTrue();
    assertThat(
            agentExecutionService.completeVersioned(
                executionId, versionTwo, 25, 2, versionTwoEvent))
        .isFalse();
    assertThat(
            agentExecutionService.completeVersioned(
                executionId, staleVersion, 10, 1, UUID.randomUUID()))
        .isFalse();

    var persisted = agentResultRepository.findById(executionId).orElseThrow();
    assertThat(persisted.getResultVersion()).isEqualTo(2);
    assertThat(persisted.getSourceEventId()).isEqualTo(versionTwoEvent);
    assertThat(persisted.getResult().path("summary").asText()).contains("公共知识");
  }

  @Test
  void lateOlderExecutionDoesNotReplaceTheNewerAgentProjection() {
    UUID taskId = taskService.create(TaskType.PLAN_GENERATION, UUID.randomUUID()).taskId();
    UUID olderExecution = agentExecutionService.start(taskId, AgentName.RESEARCH);
    UUID newerExecution = agentExecutionService.start(taskId, AgentName.RESEARCH);

    agentExecutionService.completeVersioned(
        newerExecution, agentResult(taskId, "new-result"), 20, 1, UUID.randomUUID());
    agentExecutionService.completeVersioned(
        olderExecution, agentResult(taskId, "late-old-result"), 40, 2, UUID.randomUUID());

    var research =
        agentExecutionService.list(taskService.get(taskId)).stream()
            .filter(item -> item.agentName().equals(AgentName.RESEARCH.cardName()))
            .findFirst()
            .orElseThrow();
    assertThat(research.agentExecutionId()).isEqualTo(newerExecution);
  }

  @Test
  void postgresRemainsTheSourceOfTruthWhenRedisProjectionIsAbsent() {
    UUID taskId = taskService.create(TaskType.PLAN_GENERATION, UUID.randomUUID()).taskId();
    taskService.transition(taskId, TaskStatus.RUNNING, "DATABASE_SOURCE", 30, "Durable state");
    clearInvocations(runtimeState);

    var restored = taskService.get(taskId);

    assertThat(restored)
        .satisfies(
            task -> {
              assertThat(task.status()).isEqualTo(TaskStatus.RUNNING);
              assertThat(task.stage()).isEqualTo("DATABASE_SOURCE");
              assertThat(task.traceId()).matches("[0-9a-f]{32}");
            });
    verify(runtimeState).cacheTask(restored);
  }

  @Test
  void completedPlanWinsAgainstALateCancellationWithoutSplittingTaskState() {
    UUID tripId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    Instant now = Instant.parse("2026-09-11T02:00:00Z");
    jdbcTemplate.update(
        """
        INSERT INTO trip (
            trip_id, user_id, title, time_zone, status, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        tripId,
        UUID.randomUUID(),
        "Stage six synthetic trip",
        "Asia/Shanghai",
        "READY",
        Timestamp.from(now),
        Timestamp.from(now));
    TravelPlanEntity plan =
        new TravelPlanEntity(
            planId,
            tripId,
            planId,
            null,
            "stage6-plan",
            PlanStrategy.BALANCED,
            1,
            "Synthetic plan",
            "",
            objectMapper.createArrayNode(),
            objectMapper.createObjectNode(),
            objectMapper.createArrayNode(),
            objectMapper.createArrayNode(),
            "STAGE6_TEST",
            objectMapper.createObjectNode(),
            "",
            false,
            now);
    planRepository.saveAndFlush(plan);
    UUID taskId = taskService.create(TaskType.PLAN_GENERATION, planId).taskId();
    plan.markReady("Completed before cancellation", now.plusSeconds(1));
    planRepository.saveAndFlush(plan);

    assertThatThrownBy(() -> taskService.cancel(taskId))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", "TASK_COMPLETION_WON");
    assertThat(taskService.get(taskId).status()).isEqualTo(TaskStatus.QUEUED);
    assertThat(planRepository.findById(planId).orElseThrow().getStatus())
        .isEqualTo(PlanStatus.READY);
  }

  @Test
  void consistencySqlRunsAgainstTheMigratedPostgresSchema() throws Exception {
    Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    Path sqlPath = workingDirectory.resolve("qa/consistency/stage6_consistency.sql");
    if (!Files.exists(sqlPath)) {
      sqlPath = workingDirectory.resolve("../../qa/consistency/stage6_consistency.sql").normalize();
    }
    String sql =
        Files.readString(sqlPath)
            .replace(":stale_outbox_seconds", "300")
            .replace(":long_running_task_seconds", "300")
            .replace(":stale_inbox_seconds", "300")
            .replace(":sample_limit", "20");

    List<ConsistencyCheckRow> checks =
        jdbcTemplate.query(
            sql,
            (resultSet, rowNumber) ->
                new ConsistencyCheckRow(resultSet.getString(1), resultSet.getLong(2)));

    assertThat(checks)
        .extracting(ConsistencyCheckRow::name)
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "orphanTasks",
                "orphanPlans",
                "duplicatePlanVersions",
                "unreferencedEvidence",
                "unfinishedKafkaOutbox",
                "longRunningTasks",
                "staleInboxClaims",
                "missingTaskResultPlans",
                "successfulTasksWithoutPlans",
                "taskEventsWithoutProvenance",
                "incompleteDeadLetterAudits"));
    assertThat(checks).allSatisfy(check -> assertThat(check.violations()).isZero());
  }

  private EventEnvelope event(UUID taskId, String eventType) {
    return event(taskId, eventType, "test-event-key-" + UUID.randomUUID());
  }

  private EventEnvelope event(UUID taskId, String eventType, String idempotencyKey) {
    UUID eventId = UUID.randomUUID();
    return new EventEnvelope(
        eventId,
        eventType,
        EventEnvelope.CURRENT_VERSION,
        Instant.now(),
        taskId,
        taskId,
        UUID.randomUUID().toString().replace("-", ""),
        UUID.randomUUID(),
        taskId,
        idempotencyKey,
        objectMapper.createObjectNode().put("planId", UUID.randomUUID().toString()));
  }

  private AgentResultEnvelope<JsonNode> agentResult(UUID taskId, String value) {
    return new AgentResultEnvelope<>(
        taskId,
        AgentName.RESEARCH,
        AgentStatus.SUCCEEDED,
        objectMapper.createObjectNode().put("value", value),
        List.of(),
        List.of(),
        UUID.randomUUID().toString().replace("-", ""));
  }

  private record ConsistencyCheckRow(String name, long violations) {}
}
