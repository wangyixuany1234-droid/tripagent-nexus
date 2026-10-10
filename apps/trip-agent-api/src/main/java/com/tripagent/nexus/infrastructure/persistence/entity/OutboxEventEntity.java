package com.tripagent.nexus.infrastructure.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripagent.nexus.domain.model.DomainEnums.EventDeliveryStatus;
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
@Table(name = "event_outbox")
public class OutboxEventEntity {

  @Id
  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(nullable = false, length = 200)
  private String topic;

  @Column(name = "event_key", nullable = false, length = 200)
  private String eventKey;

  @Column(name = "event_type", nullable = false, length = 200)
  private String eventType;

  @Column(name = "event_version", nullable = false, length = 20)
  private String eventVersion;

  @Column(name = "correlation_id", nullable = false)
  private UUID correlationId;

  @Column(name = "causation_id", nullable = false)
  private UUID causationId;

  @Column(name = "idempotency_key", nullable = false, length = 128)
  private String idempotencyKey;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode payload;

  @Column(name = "trace_id", nullable = false, length = 32)
  private String traceId;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "max_attempts", nullable = false)
  private int maxAttempts;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private EventDeliveryStatus status;

  @Column(name = "last_error_code", length = 100)
  private String lastErrorCode;

  @Column(name = "last_error_message", length = 1000)
  private String lastErrorMessage;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected OutboxEventEntity() {}

  public OutboxEventEntity(
      UUID eventId,
      UUID aggregateId,
      String topic,
      String eventKey,
      String eventType,
      String eventVersion,
      UUID correlationId,
      UUID causationId,
      String idempotencyKey,
      JsonNode payload,
      String traceId,
      int maxAttempts,
      Instant now) {
    this.eventId = eventId;
    this.aggregateId = aggregateId;
    this.topic = topic;
    this.eventKey = eventKey;
    this.eventType = eventType;
    this.eventVersion = eventVersion;
    this.correlationId = correlationId;
    this.causationId = causationId;
    this.idempotencyKey = idempotencyKey;
    this.payload = payload;
    this.traceId = traceId;
    this.attempts = 0;
    this.maxAttempts = maxAttempts;
    this.status = EventDeliveryStatus.PENDING;
    this.nextAttemptAt = now;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getEventId() {
    return eventId;
  }

  public String getTopic() {
    return topic;
  }

  public String getEventKey() {
    return eventKey;
  }

  public String getEventType() {
    return eventType;
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

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public JsonNode getPayload() {
    return payload;
  }

  public String getTraceId() {
    return traceId;
  }

  public int getAttempts() {
    return attempts;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public EventDeliveryStatus getStatus() {
    return status;
  }

  public void markPublished(Instant now) {
    this.publishedAt = now;
    this.status = EventDeliveryStatus.PUBLISHED;
    this.updatedAt = now;
  }

  public void markAttemptFailed(
      String errorCode, String errorMessage, Instant nextAttemptAt, Instant now) {
    this.attempts++;
    this.nextAttemptAt = nextAttemptAt;
    this.lastErrorCode = errorCode;
    this.lastErrorMessage = errorMessage;
    this.updatedAt = now;
  }

  public void markDeadLettered(String errorCode, String errorMessage, Instant now) {
    this.status = EventDeliveryStatus.DEAD_LETTERED;
    this.lastErrorCode = errorCode;
    this.lastErrorMessage = errorMessage;
    this.updatedAt = now;
  }
}
