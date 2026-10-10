package com.tripagent.nexus.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task_event")
public class TaskEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long sequence;

  @Column(name = "event_id", nullable = false, unique = true)
  private UUID eventId;

  @Column(name = "task_id", nullable = false)
  private UUID taskId;

  @Column(nullable = false, length = 100)
  private String stage;

  @Column(nullable = false)
  private int progress;

  @Column(nullable = false, length = 1000)
  private String message;

  @Column(name = "trace_id", nullable = false, length = 32)
  private String traceId;

  @Column(name = "source_event_id", nullable = false)
  private UUID sourceEventId;

  @Column(name = "source_event_type", nullable = false, length = 200)
  private String sourceEventType;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  protected TaskEventEntity() {}

  public TaskEventEntity(
      UUID eventId, UUID taskId, String stage, int progress, String message, Instant occurredAt) {
    this(
        eventId,
        taskId,
        stage,
        progress,
        message,
        eventId.toString().replace("-", ""),
        eventId,
        "task.state.changed",
        occurredAt);
  }

  public TaskEventEntity(
      UUID eventId,
      UUID taskId,
      String stage,
      int progress,
      String message,
      String traceId,
      UUID sourceEventId,
      String sourceEventType,
      Instant occurredAt) {
    this.eventId = eventId;
    this.taskId = taskId;
    this.stage = stage;
    this.progress = progress;
    this.message = message;
    this.traceId = traceId;
    this.sourceEventId = sourceEventId;
    this.sourceEventType = sourceEventType;
    this.occurredAt = occurredAt;
  }

  public Long getSequence() {
    return sequence;
  }

  public UUID getEventId() {
    return eventId;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public String getStage() {
    return stage;
  }

  public int getProgress() {
    return progress;
  }

  public String getMessage() {
    return message;
  }

  public String getTraceId() {
    return traceId;
  }

  public UUID getSourceEventId() {
    return sourceEventId;
  }

  public String getSourceEventType() {
    return sourceEventType;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
