package com.tripagent.nexus.application.task;

import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.domain.model.TaskEvent;
import com.tripagent.nexus.infrastructure.persistence.entity.TaskEventEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TaskRecordEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.TaskEventRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TaskRecordRepository;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class TaskEventStream {

  private static final long TIMEOUT_MILLIS = 30 * 60 * 1000L;

  private final TaskRecordRepository taskRepository;
  private final TaskEventRepository eventRepository;
  private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters =
      new ConcurrentHashMap<>();

  public TaskEventStream(TaskRecordRepository taskRepository, TaskEventRepository eventRepository) {
    this.taskRepository = taskRepository;
    this.eventRepository = eventRepository;
  }

  public SseEmitter open(UUID taskId, UUID lastEventId) {
    TaskRecordEntity task =
        taskRepository
            .findById(taskId)
            .orElseThrow(
                () ->
                    new ApiException(
                        "TASK_NOT_FOUND", HttpStatus.NOT_FOUND, "Task was not found."));
    List<TaskEvent> replay = replay(taskId, lastEventId);
    SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
    try {
      for (TaskEvent event : replay) {
        send(emitter, event);
      }
      if (task.getStatus().isTerminal()) {
        emitter.complete();
        return emitter;
      }
    } catch (IOException exception) {
      emitter.completeWithError(exception);
      return emitter;
    }

    CopyOnWriteArrayList<SseEmitter> taskEmitters =
        emitters.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>());
    taskEmitters.add(emitter);
    emitter.onCompletion(() -> remove(taskId, emitter));
    emitter.onTimeout(() -> remove(taskId, emitter));
    emitter.onError(exception -> remove(taskId, emitter));
    return emitter;
  }

  public void publish(TaskEvent event, boolean terminal) {
    List<SseEmitter> taskEmitters =
        emitters.getOrDefault(event.taskId(), new CopyOnWriteArrayList<>());
    for (SseEmitter emitter : taskEmitters) {
      try {
        send(emitter, event);
        if (terminal) {
          emitter.complete();
        }
      } catch (IOException exception) {
        emitter.completeWithError(exception);
        remove(event.taskId(), emitter);
      }
    }
    if (terminal) {
      emitters.remove(event.taskId());
    }
  }

  public List<TaskEvent> replay(UUID taskId, UUID lastEventId) {
    if (lastEventId == null) {
      return eventRepository.findByTaskIdOrderBySequenceAsc(taskId).stream()
          .map(TaskEventStream::toDomain)
          .toList();
    }
    TaskEventEntity lastEvent =
        eventRepository
            .findByTaskIdAndEventId(taskId, lastEventId)
            .orElseThrow(
                () ->
                    new ApiException(
                        "TASK_EVENT_GONE",
                        HttpStatus.GONE,
                        "Last-Event-ID is not available for this task."));
    return eventRepository
        .findByTaskIdAndSequenceGreaterThanOrderBySequenceAsc(taskId, lastEvent.getSequence())
        .stream()
        .map(TaskEventStream::toDomain)
        .toList();
  }

  private static void send(SseEmitter emitter, TaskEvent event) throws IOException {
    emitter.send(
        SseEmitter.event().id(event.eventId().toString()).name("task-progress").data(event));
  }

  private void remove(UUID taskId, SseEmitter emitter) {
    List<SseEmitter> taskEmitters = emitters.get(taskId);
    if (taskEmitters != null) {
      taskEmitters.remove(emitter);
      if (taskEmitters.isEmpty()) {
        emitters.remove(taskId, taskEmitters);
      }
    }
  }

  private static TaskEvent toDomain(TaskEventEntity entity) {
    return new TaskEvent(
        entity.getEventId(),
        entity.getTaskId(),
        entity.getStage(),
        entity.getProgress(),
        entity.getMessage(),
        entity.getOccurredAt());
  }
}
