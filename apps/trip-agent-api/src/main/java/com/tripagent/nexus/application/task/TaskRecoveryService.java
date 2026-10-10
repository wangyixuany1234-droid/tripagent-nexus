package com.tripagent.nexus.application.task;

import com.tripagent.nexus.application.planning.PlanningCheckpointService;
import com.tripagent.nexus.application.planning.PlanningCommandService;
import com.tripagent.nexus.application.planning.PlanningWorkflowService;
import com.tripagent.nexus.application.service.DocumentOutboxService;
import com.tripagent.nexus.application.service.TaskApplicationService;
import com.tripagent.nexus.controller.dto.ApiModels.TaskAccepted;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.domain.model.TaskRecord;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.DocumentEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TaskRecordEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.DocumentRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TaskRecordRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelPlanRepository;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
    name = "app.async-governance.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TaskRecoveryService {

  private final TaskRecordRepository taskRepository;
  private final DocumentRepository documentRepository;
  private final TravelPlanRepository planRepository;
  private final TaskApplicationService taskService;
  private final DocumentOutboxService documentOutboxService;
  private final PlanningCommandService planningCommandService;
  private final RedisRuntimeStateService runtimeState;
  private final AsyncGovernanceProperties properties;
  private final Clock clock;
  private final PlanningCheckpointService checkpoints;
  private final ObjectProvider<PlanningWorkflowService> workflows;

  public TaskRecoveryService(
      TaskRecordRepository taskRepository,
      DocumentRepository documentRepository,
      TravelPlanRepository planRepository,
      TaskApplicationService taskService,
      DocumentOutboxService documentOutboxService,
      PlanningCommandService planningCommandService,
      RedisRuntimeStateService runtimeState,
      AsyncGovernanceProperties properties,
      Clock clock,
      PlanningCheckpointService checkpoints,
      ObjectProvider<PlanningWorkflowService> workflows) {
    this.taskRepository = taskRepository;
    this.documentRepository = documentRepository;
    this.planRepository = planRepository;
    this.taskService = taskService;
    this.documentOutboxService = documentOutboxService;
    this.planningCommandService = planningCommandService;
    this.runtimeState = runtimeState;
    this.properties = properties;
    this.clock = clock;
    this.checkpoints = checkpoints;
    this.workflows = workflows;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recoverAfterStartup() {
    recoverStaleTasks();
  }

  @Scheduled(fixedDelayString = "${app.async-governance.recovery-poll-interval:30s}")
  public void recoverStaleTasks() {
    RedisRuntimeStateService.LockHandle lock = runtimeState.tryAcquireLock("task-recovery");
    if (!lock.acquired()) {
      return;
    }
    try {
      List<TaskRecordEntity> stale =
          taskRepository.findTop100ByStatusInAndHeartbeatAtBeforeOrderByHeartbeatAtAsc(
              List.of(TaskStatus.QUEUED, TaskStatus.RUNNING),
              clock.instant().minus(properties.getStaleTaskThreshold()));
      for (TaskRecordEntity task : stale) {
        try {
          recoverOrFail(task);
        } catch (RuntimeException failure) {
          org.slf4j.LoggerFactory.getLogger(TaskRecoveryService.class)
              .warn(
                  "Task recovery failed taskId={} traceId={} errorType={}",
                  task.getTaskId(),
                  task.getTraceId(),
                  failure.getClass().getSimpleName());
        }
      }
    } finally {
      runtimeState.releaseLock(lock);
    }
  }

  private void recoverOrFail(TaskRecordEntity entity) {
    var acquired = checkpoints.tryLock(entity.getTaskId());
    if (acquired.isEmpty()) return;
    try (var lock = acquired.orElseThrow()) {
      var current = taskRepository.findById(entity.getTaskId()).orElse(null);
      if (current == null
          || current.getStatus().isTerminal()
          || current
              .getHeartbeatAt()
              .isAfter(clock.instant().minus(properties.getStaleTaskThreshold()))) return;
      recoverLocked(current, lock);
    }
  }

  private void recoverLocked(
      TaskRecordEntity entity, PlanningCheckpointService.ExecutionLock lock) {
    if (entity.getRecoveryAttempts() >= properties.getMaxRecoveryAttempts()) {
      taskService.fail(
          entity.getTaskId(),
          "TASK_RECOVERY_EXHAUSTED",
          "The task could not be safely resumed after the service restart.",
          false);
      return;
    }
    TaskRecord task = taskService.recordRecoveryAttempt(entity.getTaskId());
    if (task.status() == TaskStatus.RUNNING && reconcileCompletedPlanning(task)) {
      return;
    }
    if (task.status() == TaskStatus.RUNNING && checkpoints.resumable(task.taskId())) {
      PlanningWorkflowService workflow = workflows.getIfAvailable();
      if (workflow != null) {
        try {
          workflow.executeLocked(task.taskId(), task.resourceId(), task.type(), lock);
        } finally {
          checkpoints.completeRecoveredInbox(task.taskId());
        }
        return;
      }
    }
    if (task.status() == TaskStatus.QUEUED || "RETRY_SCHEDULED".equals(task.stage())) {
      redispatch(task);
      return;
    }
    taskService.fail(
        task.taskId(),
        "TASK_RECOVERY_UNSAFE",
        "The service restarted during a non-repeatable step; no duplicate data was generated.",
        false);
  }

  private boolean reconcileCompletedPlanning(TaskRecord task) {
    if ((task.type() != TaskType.PLAN_GENERATION && task.type() != TaskType.PLAN_REPLAN)
        || task.resultResourceIds().isEmpty()) {
      return false;
    }
    Set<java.util.UUID> resultIds = new LinkedHashSet<>(task.resultResourceIds());
    var plans = planRepository.findAllById(resultIds);
    if (plans.size() != resultIds.size()
        || plans.stream()
            .anyMatch(
                plan ->
                    plan.getStatus() == PlanStatus.GENERATING
                        || plan.getStatus() == PlanStatus.FAILED
                        || plan.getStatus() == PlanStatus.CANCELLED)) {
      return false;
    }
    if (plans.stream().anyMatch(plan -> plan.getStatus() == PlanStatus.PARTIAL)) {
      taskService.completePartially(
          task.taskId(),
          "Planning completion was reconciled after service restart.",
          List.of("RECOVERED_COMPLETION"));
    } else {
      taskService.transition(
          task.taskId(),
          TaskStatus.SUCCEEDED,
          "COMPLETED",
          100,
          "Planning completion was reconciled after service restart.");
    }
    return true;
  }

  private void redispatch(TaskRecord task) {
    TaskAccepted accepted =
        new TaskAccepted(
            task.taskId(), task.resourceId(), task.status(), task.traceId(), task.createdAt());
    if (task.type() == TaskType.DOCUMENT_PARSE) {
      DocumentEntity document =
          documentRepository
              .findById(task.resourceId())
              .orElseThrow(() -> new IllegalStateException("DOCUMENT_NOT_FOUND"));
      documentOutboxService.enqueueRequested(document, accepted, "task-recovery-" + task.taskId());
    } else if (task.type() == TaskType.PLAN_GENERATION || task.type() == TaskType.PLAN_REPLAN) {
      planningCommandService.schedule(accepted, task.type());
    } else {
      taskService.fail(
          task.taskId(),
          "TASK_RECOVERY_UNSAFE",
          "Evaluation replay must be explicitly restarted with a new run.",
          false);
    }
  }
}
