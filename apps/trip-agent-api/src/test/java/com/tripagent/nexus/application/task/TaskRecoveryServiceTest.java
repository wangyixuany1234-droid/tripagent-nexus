package com.tripagent.nexus.application.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tripagent.nexus.application.planning.PlanningCommandService;
import com.tripagent.nexus.application.service.DocumentOutboxService;
import com.tripagent.nexus.application.service.TaskApplicationService;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.domain.model.TaskRecord;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.TaskRecordEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelPlanEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.DocumentRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TaskRecordRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelPlanRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskRecoveryServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-05T01:00:00Z");

  @Mock private TaskRecordRepository taskRepository;
  @Mock private DocumentRepository documentRepository;
  @Mock private TravelPlanRepository planRepository;
  @Mock private TaskApplicationService taskService;
  @Mock private DocumentOutboxService documentOutboxService;
  @Mock private PlanningCommandService planningCommandService;
  @Mock private RedisRuntimeStateService runtimeState;
  @Mock private com.tripagent.nexus.application.planning.PlanningCheckpointService checkpoints;

  @Mock
  private org.springframework.beans.factory.ObjectProvider<
          com.tripagent.nexus.application.planning.PlanningWorkflowService>
      workflows;

  private TaskRecoveryService recoveryService;

  @BeforeEach
  void setUp() {
    AsyncGovernanceProperties properties = new AsyncGovernanceProperties();
    properties.setMaxRecoveryAttempts(1);
    when(runtimeState.tryAcquireLock("task-recovery"))
        .thenReturn(new RedisRuntimeStateService.LockHandle("lock", "token", true));
    when(checkpoints.tryLock(any()))
        .thenReturn(
            java.util.Optional.of(
                mock(
                    com.tripagent.nexus.application.planning.PlanningCheckpointService.ExecutionLock
                        .class)));
    recoveryService =
        new TaskRecoveryService(
            taskRepository,
            documentRepository,
            planRepository,
            taskService,
            documentOutboxService,
            planningCommandService,
            runtimeState,
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC),
            checkpoints,
            workflows);
  }

  @Test
  void staleRunningTaskIsExplicitlyFailedInsteadOfRepeatingUnsafeSideEffects() {
    UUID taskId = UUID.randomUUID();
    UUID resourceId = UUID.randomUUID();
    TaskRecordEntity entity =
        new TaskRecordEntity(
            taskId,
            TaskType.PLAN_GENERATION,
            resourceId,
            UUID.randomUUID().toString().replace("-", ""),
            NOW.minusSeconds(600));
    entity.transition(TaskStatus.QUEUED, "QUEUED", 0, "Queued", NOW.minusSeconds(590));
    entity.transition(TaskStatus.RUNNING, "AGENTS_RUNNING", 40, "Running", NOW.minusSeconds(580));
    TaskRecord recovered =
        new TaskRecord(
            taskId,
            TaskType.PLAN_GENERATION,
            resourceId,
            TaskStatus.RUNNING,
            "AGENTS_RUNNING",
            40,
            "Running",
            entity.getTraceId(),
            List.of(),
            null,
            null,
            false,
            1,
            List.of(),
            null,
            NOW.minusSeconds(600),
            NOW.minusSeconds(580));
    when(taskRepository.findTop100ByStatusInAndHeartbeatAtBeforeOrderByHeartbeatAtAsc(
            anyCollection(), any(Instant.class)))
        .thenReturn(List.of(entity));
    when(taskService.recordRecoveryAttempt(taskId)).thenReturn(recovered);
    when(taskRepository.findById(taskId)).thenReturn(java.util.Optional.of(entity));

    recoveryService.recoverStaleTasks();

    verify(taskService)
        .fail(
            taskId,
            "TASK_RECOVERY_UNSAFE",
            "The service restarted during a non-repeatable step; no duplicate data was generated.",
            false);
    verify(runtimeState)
        .releaseLock(new RedisRuntimeStateService.LockHandle("lock", "token", true));
  }

  @Test
  void staleRunningTaskWithCompletedPlanIsReconciledToSucceeded() {
    UUID taskId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    TaskRecordEntity entity =
        new TaskRecordEntity(
            taskId,
            TaskType.PLAN_GENERATION,
            planId,
            UUID.randomUUID().toString().replace("-", ""),
            NOW.minusSeconds(600));
    entity.transition(TaskStatus.QUEUED, "QUEUED", 0, "Queued", NOW.minusSeconds(590));
    entity.transition(
        TaskStatus.RUNNING, "RESULTS_PERSISTED", 90, "Results persisted", NOW.minusSeconds(580));
    TaskRecord recovered =
        new TaskRecord(
            taskId,
            TaskType.PLAN_GENERATION,
            planId,
            TaskStatus.RUNNING,
            "RESULTS_PERSISTED",
            90,
            "Results persisted",
            entity.getTraceId(),
            List.of(planId),
            null,
            null,
            false,
            1,
            List.of(),
            null,
            NOW.minusSeconds(600),
            NOW.minusSeconds(580));
    TravelPlanEntity completedPlan = mock(TravelPlanEntity.class);
    when(completedPlan.getStatus()).thenReturn(PlanStatus.READY);
    when(taskRepository.findTop100ByStatusInAndHeartbeatAtBeforeOrderByHeartbeatAtAsc(
            anyCollection(), any(Instant.class)))
        .thenReturn(List.of(entity));
    when(taskService.recordRecoveryAttempt(taskId)).thenReturn(recovered);
    when(taskRepository.findById(taskId)).thenReturn(java.util.Optional.of(entity));
    when(planRepository.findAllById(Set.of(planId))).thenReturn(List.of(completedPlan));

    recoveryService.recoverStaleTasks();

    verify(taskService)
        .transition(
            taskId,
            TaskStatus.SUCCEEDED,
            "COMPLETED",
            100,
            "Planning completion was reconciled after service restart.");
    verify(taskService, never()).fail(any(), any(), any(), anyBoolean());
  }
}
