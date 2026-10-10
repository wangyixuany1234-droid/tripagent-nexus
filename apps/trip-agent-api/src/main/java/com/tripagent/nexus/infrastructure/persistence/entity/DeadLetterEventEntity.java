package com.tripagent.nexus.infrastructure.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripagent.nexus.domain.model.DomainEnums.DeadLetterStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dead_letter_event")
public class DeadLetterEventEntity {

  @Id
  @Column(name = "dead_letter_id", nullable = false)
  private UUID deadLetterId;

  @Column(name = "original_event_id", nullable = false, unique = true)
  private UUID originalEventId;

  @Column(name = "original_topic", nullable = false, length = 200)
  private String originalTopic;

  @Column(name = "original_event_key", nullable = false, length = 200)
  private String originalEventKey;

  @Column(name = "original_event_type", nullable = false, length = 200)
  private String originalEventType;

  @Column(name = "event_version", nullable = false, length = 20)
  private String eventVersion;

  @Column(name = "correlation_id", nullable = false)
  private UUID correlationId;

  @Column(name = "causation_id", nullable = false)
  private UUID causationId;

  @Column(name = "trace_id", nullable = false, length = 32)
  private String traceId;

  @Column(name = "trip_id")
  private UUID tripId;

  @Column(name = "task_id")
  private UUID taskId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "original_envelope", nullable = false, columnDefinition = "jsonb")
  private JsonNode originalEnvelope;

  @Column(name = "failure_code", nullable = false, length = 100)
  private String failureCode;

  @Column(name = "failure_reason", nullable = false, length = 1000)
  private String failureReason;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private DeadLetterStatus status;

  @Column(name = "replay_count", nullable = false)
  private int replayCount;

  @Column(name = "replay_event_id")
  private UUID replayEventId;

  @Column(name = "replay_task_id")
  private UUID replayTaskId;

  @Column(name = "replay_reason", length = 500)
  private String replayReason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "replayed_at")
  private Instant replayedAt;

  protected DeadLetterEventEntity() {}

  public DeadLetterEventEntity(
      UUID deadLetterId,
      UUID originalEventId,
      String originalTopic,
      String originalEventKey,
      String originalEventType,
      String eventVersion,
      UUID correlationId,
      UUID causationId,
      String traceId,
      UUID tripId,
      UUID taskId,
      JsonNode originalEnvelope,
      String failureCode,
      String failureReason,
      Instant now) {
    this.deadLetterId = deadLetterId;
    this.originalEventId = originalEventId;
    this.originalTopic = originalTopic;
    this.originalEventKey = originalEventKey;
    this.originalEventType = originalEventType;
    this.eventVersion = eventVersion;
    this.correlationId = correlationId;
    this.causationId = causationId;
    this.traceId = traceId;
    this.tripId = tripId;
    this.taskId = taskId;
    this.originalEnvelope = originalEnvelope;
    this.failureCode = failureCode;
    this.failureReason = failureReason;
    this.status = DeadLetterStatus.PENDING;
    this.replayCount = 0;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getDeadLetterId() {
    return deadLetterId;
  }

  public UUID getOriginalEventId() {
    return originalEventId;
  }

  public String getOriginalTopic() {
    return originalTopic;
  }

  public String getOriginalEventKey() {
    return originalEventKey;
  }

  public String getOriginalEventType() {
    return originalEventType;
  }

  public String getEventVersion() {
    return eventVersion;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public UUID getCausationId() {
    return causationId;
  }

  public String getTraceId() {
    return traceId;
  }

  public UUID getTripId() {
    return tripId;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public JsonNode getOriginalEnvelope() {
    return originalEnvelope;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public DeadLetterStatus getStatus() {
    return status;
  }

  public int getReplayCount() {
    return replayCount;
  }

  public UUID getReplayEventId() {
    return replayEventId;
  }

  public String getReplayReason() {
    return replayReason;
  }

  public UUID getReplayTaskId() {
    return replayTaskId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void markReplayed(UUID eventId, UUID taskId, String reason, Instant now) {
    status = DeadLetterStatus.REPLAYED;
    replayCount++;
    replayEventId = eventId;
    replayTaskId = taskId;
    replayReason = reason;
    replayedAt = now;
    updatedAt = now;
  }
}
