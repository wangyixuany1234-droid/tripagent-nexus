package com.tripagent.nexus.infrastructure.persistence.entity;

import com.tripagent.nexus.domain.model.DomainEnums.InboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_inbox")
public class EventInboxEntity {

  @Id
  @Column(name = "inbox_id", nullable = false)
  private UUID inboxId;

  @Column(name = "consumer_name", nullable = false, length = 160)
  private String consumerName;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "event_type", nullable = false, length = 200)
  private String eventType;

  @Column(name = "event_version", nullable = false, length = 20)
  private String eventVersion;

  @Column(name = "correlation_id", nullable = false)
  private UUID correlationId;

  @Column(name = "causation_id", nullable = false)
  private UUID causationId;

  @Column(name = "trace_id", nullable = false, length = 32)
  private String traceId;

  @Column(name = "task_id")
  private UUID taskId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private InboxStatus status;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "last_error_code", length = 100)
  private String lastErrorCode;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected EventInboxEntity() {}

  public EventInboxEntity(
      UUID inboxId,
      String consumerName,
      UUID eventId,
      String eventType,
      String eventVersion,
      UUID correlationId,
      UUID causationId,
      String traceId,
      UUID taskId,
      Instant now) {
    this.inboxId = inboxId;
    this.consumerName = consumerName;
    this.eventId = eventId;
    this.eventType = eventType;
    this.eventVersion = eventVersion;
    this.correlationId = correlationId;
    this.causationId = causationId;
    this.traceId = traceId;
    this.taskId = taskId;
    this.status = InboxStatus.PROCESSING;
    this.attempts = 1;
    this.receivedAt = now;
    this.updatedAt = now;
  }

  public UUID getEventId() {
    return eventId;
  }

  public InboxStatus getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }

  public void retry(Instant now) {
    status = InboxStatus.PROCESSING;
    attempts++;
    lastErrorCode = null;
    updatedAt = now;
  }

  public void complete(Instant now) {
    status = InboxStatus.COMPLETED;
    completedAt = now;
    updatedAt = now;
  }

  public void fail(String errorCode, boolean retryable, Instant now) {
    status = retryable ? InboxStatus.RETRYABLE : InboxStatus.FAILED;
    lastErrorCode = errorCode;
    updatedAt = now;
  }
}
