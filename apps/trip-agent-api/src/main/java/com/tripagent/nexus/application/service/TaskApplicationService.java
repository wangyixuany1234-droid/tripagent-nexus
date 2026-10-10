package com.tripagent.nexus.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripagent.nexus.application.task.RedisRuntimeStateService;
import com.tripagent.nexus.application.task.TaskEventStream;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.controller.dto.ApiModels.TaskAccepted;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.domain.model.TaskEvent;
import com.tripagent.nexus.domain.model.TaskRecord;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.TaskEventEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TaskRecordEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.DocumentRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TaskEventRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TaskRecordRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelPlanRepository;
import com.tripagent.nexus.integration.messaging.EventCausationContext;
import com.tripagent.nexus.integration.messaging.EventCausationContext.SourceEvent;
import com.tripagent.nexus.integration.messaging.EventEnvelope;
import com.tripagent.nexus.observability.TraceContextProvider;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class TaskApplicationService {

  private final TaskRecordRepository taskRepository;
  private final TaskEventRepository eventRepository;
  private final DocumentRepository documentRepository;
  private final TravelPlanRepository planRepository;
  private final TaskEventStream eventStream;
  private final RedisRuntimeStateService runtimeState;
  private final EventOutboxService outboxService;
  private final EventCausationContext causationContext;
  private final AsyncGovernanceProperties governanceProperties;
  private final TraceContextProvider traceContextProvider;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public TaskApplicationService(
      TaskRecordRepository taskRepository,
      TaskEventRepository eventRepository,
      DocumentRepository documentRepository,
      TravelPlanRepository planRepository,
      TaskEventStream eventStream,
      RedisRuntimeStateService runtimeState,
      EventOutboxService outboxService,
      EventCausationContext causationContext,
      AsyncGovernanceProperties governanceProperties,
      TraceContextProvider traceContextProvider,
      ObjectMapper objectMapper,
      Clock clock) {
    this.taskRepository = taskRepository;
    this.eventRepository = eventRepository;
    this.documentRepository = documentRepository;
    this.planRepository = planRepository;
    this.eventStream = eventStream;
    this.runtimeState = runtimeState;
    this.outboxService = outboxService;
    this.causationContext = causationContext;
    this.governanceProperties = governanceProperties;
    this.traceContextProvider = traceContextProvider;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public TaskAccepted create(TaskType type, UUID resourceId) {
    Instant now = clock.instant();
    TaskRecordEntity task =
        new TaskRecordEntity(
            UUID.randomUUID(), type, resourceId, traceContextProvider.currentOrNewTraceId(), now);
    taskRepository.saveAndFlush(task);
    appendEvent(task, now);
    task.transition(TaskStatus.QUEUED, "QUEUED", 0, "Task queued", now);
    taskRepository.save(task);
    appendEvent(task, now);
    TaskRecord domain = toDomain(task);
    runtimeState.cacheTask(domain);
    return new TaskAccepted(
        task.getTaskId(), resourceId, task.getStatus(), task.getTraceId(), task.getCreatedAt());
  }

  @Transactional
  public TaskAccepted createRetry(UUID originalTaskId) {
    TaskRecordEntity original = requireTask(originalTaskId);
    if (original.getStatus() != TaskStatus.FAILED) {
      throw new ApiException(
          "TASK_NOT_REPLAYABLE",
          HttpStatus.CONFLICT,
          "Only a failed task can be replayed as a new task.");
    }
    Instant now = clock.instant();
    TaskRecordEntity retry =
        new TaskRecordEntity(
            UUID.randomUUID(),
            original.getType(),
            original.getResourceId(),
            original.getTraceId(),
            original.getTaskId(),
            now);
    taskRepository.saveAndFlush(retry);
    appendEvent(retry, now);
    retry.transition(TaskStatus.QUEUED, "QUEUED", 0, "Replay task queued", now);
    taskRepository.save(retry);
    appendEvent(retry, now);
    runtimeState.cacheTask(toDomain(retry));
    return toAccepted(retry);
  }

  @Transactional(readOnly = true)
  public TaskRecord get(UUID taskId) {
    TaskRecord task = toDomain(requireTask(taskId));
    runtimeState.cacheTask(task);
    return task;
  }

  @Transactional(readOnly = true)
  public TaskAccepted findAccepted(TaskType type, UUID resourceId) {
    TaskRecordEntity task =
        taskRepository
            .findFirstByTypeAndResourceIdOrderByCreatedAtDesc(type, resourceId)
            .orElseThrow(
                () ->
                    new ApiException(
                        "TASK_NOT_FOUND", HttpStatus.NOT_FOUND, "Task was not found."));
    return toAccepted(task);
  }

  @Transactional
  public TaskRecord transition(
      UUID taskId, TaskStatus status, String stage, int progress, String message) {
    TaskRecordEntity task = requireTask(taskId);
    Instant now = clock.instant();
    task.transition(status, stage, progress, message, now);
    taskRepository.save(task);
    TaskEvent event = appendEvent(task, now);
    eventStream.publish(event, status.isTerminal());
    runtimeState.cacheSseEvent(event);
    TaskRecord domain = toDomain(task);
    runtimeState.cacheTask(domain);
    return domain;
  }

  @Transactional
  public void setResultResources(UUID taskId, List<UUID> resourceIds) {
    TaskRecordEntity task = requireTask(taskId);
    task.setResultResourceIds(objectMapper.valueToTree(resourceIds), clock.instant());
    taskRepository.save(task);
    runtimeState.cacheTask(toDomain(task));
  }

  @Transactional
  public TaskRecord fail(UUID taskId, String code, String reason, boolean retryable) {
    TaskRecordEntity task = requireTask(taskId);
    if (task.getStatus().isTerminal()) {
      return toDomain(task);
    }
    Instant now = clock.instant();
    failResource(task, code, now);
    task.setFailure(code, reason, retryable, now);
    task.transition(TaskStatus.FAILED, "FAILED", 100, reason, now);
    taskRepository.save(task);
    enqueueFailure(task, code, reason, retryable, now);
    TaskEvent event = appendEvent(task, now);
    eventStream.publish(event, true);
    runtimeState.cacheSseEvent(event);
    TaskRecord domain = toDomain(task);
    runtimeState.cacheTask(domain);
    return domain;
  }

  @Transactional
  public TaskRecord completePartially(
      UUID taskId, String message, List<String> degradedComponents) {
    TaskRecordEntity task = requireTask(taskId);
    Instant now = clock.instant();
    task.setDegradedComponents(objectMapper.valueToTree(degradedComponents), now);
    task.transition(TaskStatus.PARTIAL_SUCCESS, "PARTIAL_SUCCESS", 100, message, now);
    taskRepository.save(task);
    TaskEvent event = appendEvent(task, now);
    eventStream.publish(event, true);
    runtimeState.cacheSseEvent(event);
    TaskRecord domain = toDomain(task);
    runtimeState.cacheTask(domain);
    return domain;
  }

  @Transactional
  public TaskRecord recordRecoveryAttempt(UUID taskId) {
    TaskRecordEntity task = requireTask(taskId);
    task.recordRecoveryAttempt(clock.instant());
    taskRepository.save(task);
    TaskRecord domain = toDomain(task);
    runtimeState.cacheTask(domain);
    return domain;
  }

  @Transactional(readOnly = true)
  public boolean isCancellationRequested(UUID taskId) {
    TaskRecordEntity task = requireTask(taskId);
    return task.isCancelRequested() || task.getStatus() == TaskStatus.CANCELLED;
  }

  @Transactional
  public TaskRecord cancel(UUID taskId) {
    TaskRecordEntity task = requireTask(taskId);
    if (task.getStatus().isTerminal()) {
      throw new ApiException(
          "TASK_ALREADY_TERMINAL", HttpStatus.CONFLICT, "A terminal task cannot be cancelled.");
    }
    Instant now = clock.instant();
    task.requestCancellation(now);
    task.transition(TaskStatus.CANCELLED, "CANCELLED", task.getProgress(), "Task cancelled", now);
    taskRepository.save(task);
    TaskEvent event = appendEvent(task, now);
    cancelResource(task, now);
    eventStream.publish(event, true);
    runtimeState.cacheSseEvent(event);
    TaskRecord domain = toDomain(task);
    runtimeState.cacheTask(domain);
    return domain;
  }

  public SseEmitter stream(UUID taskId, UUID lastEventId) {
    return eventStream.open(taskId, lastEventId);
  }

  private void cancelResource(TaskRecordEntity task, Instant now) {
    if (task.getType() == TaskType.DOCUMENT_PARSE) {
      documentRepository
          .findById(task.getResourceId())
          .ifPresent(
              document -> {
                document.cancel(now);
                documentRepository.save(document);
              });
    } else if (task.getType() == TaskType.PLAN_GENERATION
        || task.getType() == TaskType.PLAN_REPLAN) {
      planRepository
          .findById(task.getResourceId())
          .ifPresent(
              plan -> {
                if (plan.getStatus() != PlanStatus.GENERATING) {
                  throw new ApiException(
                      "TASK_COMPLETION_WON",
                      HttpStatus.CONFLICT,
                      "The task result was committed before cancellation.");
                }
                plan.cancel(now);
                planRepository.save(plan);
              });
    }
  }

  private void failResource(TaskRecordEntity task, String code, Instant now) {
    if (task.getType() == TaskType.DOCUMENT_PARSE) {
      documentRepository
          .findById(task.getResourceId())
          .ifPresent(
              document -> {
                document.markFailed(now);
                documentRepository.save(document);
              });
    } else if (task.getType() == TaskType.PLAN_GENERATION
        || task.getType() == TaskType.PLAN_REPLAN) {
      planRepository
          .findById(task.getResourceId())
          .ifPresent(
              plan -> {
                plan.markFailed(code, now);
                planRepository.save(plan);
              });
    }
  }

  private TaskEvent appendEvent(TaskRecordEntity task, Instant now) {
    UUID eventId = UUID.randomUUID();
    SourceEvent source =
        causationContext
            .current()
            .orElse(new SourceEvent(eventId, "task.state.changed", task.getTraceId()));
    TaskEventEntity event =
        eventRepository.save(
            new TaskEventEntity(
                eventId,
                task.getTaskId(),
                task.getStage(),
                task.getProgress(),
                task.getMessage(),
                source.traceId(),
                source.eventId(),
                source.eventType(),
                now));
    return new TaskEvent(
        event.getEventId(),
        event.getTaskId(),
        event.getStage(),
        event.getProgress(),
        event.getMessage(),
        event.getOccurredAt());
  }

  private void enqueueFailure(
      TaskRecordEntity task, String code, String reason, boolean retryable, Instant now) {
    if (!governanceProperties.isEnabled()) {
      return;
    }
    UUID tripId;
    if (task.getType() == TaskType.DOCUMENT_PARSE) {
      tripId =
          documentRepository
              .findById(task.getResourceId())
              .map(document -> document.getTripId())
              .orElse(null);
    } else if (task.getType() == TaskType.PLAN_GENERATION
        || task.getType() == TaskType.PLAN_REPLAN) {
      tripId =
          planRepository.findById(task.getResourceId()).map(plan -> plan.getTripId()).orElse(null);
    } else {
      tripId = null;
    }
    if (tripId == null) {
      return;
    }
    ObjectNode failure = objectMapper.createObjectNode();
    failure.put("code", code);
    failure.put("message", reason);
    failure.put("retryable", retryable);
    failure.set("details", objectMapper.createArrayNode());
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("failedStage", failedStage(task.getType()));
    payload.set("failure", failure);
    payload.put("failedAt", now.toString());
    EventEnvelope event =
        new EventEnvelope(
            UUID.randomUUID(),
            "task.failed",
            EventEnvelope.CURRENT_VERSION,
            now,
            task.getTaskId(),
            task.getTaskId(),
            task.getTraceId(),
            tripId,
            task.getTaskId(),
            "task-failed-" + task.getTaskId(),
            payload);
    outboxService.enqueue(
        governanceProperties.getTaskFailedTopic(),
        task.getTaskId().toString(),
        task.getTaskId(),
        event);
  }

  private static String failedStage(TaskType taskType) {
    return switch (taskType) {
      case DOCUMENT_PARSE -> "PARSING";
      case PLAN_GENERATION, PLAN_REPLAN -> "PLANNING";
      case EVALUATION_REPLAY -> "EVALUATION";
    };
  }

  private TaskRecordEntity requireTask(UUID taskId) {
    return taskRepository
        .findById(taskId)
        .orElseThrow(
            () -> new ApiException("TASK_NOT_FOUND", HttpStatus.NOT_FOUND, "Task was not found."));
  }

  private TaskRecord toDomain(TaskRecordEntity entity) {
    return new TaskRecord(
        entity.getTaskId(),
        entity.getType(),
        entity.getResourceId(),
        entity.getStatus(),
        entity.getStage(),
        entity.getProgress(),
        entity.getMessage(),
        entity.getTraceId(),
        objectMapper.convertValue(
            entity.getResultResourceIds(),
            objectMapper.getTypeFactory().constructCollectionType(List.class, UUID.class)),
        entity.getFailureCode(),
        entity.getFailureReason(),
        entity.isRetryable(),
        entity.getRecoveryAttempts(),
        objectMapper.convertValue(
            entity.getDegradedComponents(),
            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)),
        entity.getRetryOfTaskId(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private static TaskAccepted toAccepted(TaskRecordEntity entity) {
    return new TaskAccepted(
        entity.getTaskId(),
        entity.getResourceId(),
        entity.getStatus(),
        entity.getTraceId(),
        entity.getCreatedAt());
  }
}
