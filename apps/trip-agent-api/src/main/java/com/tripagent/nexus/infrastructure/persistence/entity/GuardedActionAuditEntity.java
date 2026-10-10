package com.tripagent.nexus.infrastructure.persistence.entity;

import com.tripagent.nexus.domain.model.DomainEnums.ActionAuditEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "guarded_action_audit")
public class GuardedActionAuditEntity {

  @Id
  @Column(name = "audit_id", nullable = false)
  private UUID auditId;

  @Column(name = "action_id", nullable = false)
  private UUID actionId;

  @Column(name = "actor_id", nullable = false, length = 128)
  private String actorId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 32)
  private ActionAuditEvent eventType;

  @Column(length = 500)
  private String reason;

  @Column(name = "trace_id", nullable = false, length = 32)
  private String traceId;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  protected GuardedActionAuditEntity() {}

  public GuardedActionAuditEntity(
      UUID auditId,
      UUID actionId,
      String actorId,
      ActionAuditEvent eventType,
      String reason,
      String traceId,
      Instant occurredAt) {
    this.auditId = auditId;
    this.actionId = actionId;
    this.actorId = actorId;
    this.eventType = eventType;
    this.reason = reason;
    this.traceId = traceId;
    this.occurredAt = occurredAt;
  }

  public UUID getAuditId() {
    return auditId;
  }

  public UUID getActionId() {
    return actionId;
  }

  public String getActorId() {
    return actorId;
  }

  public ActionAuditEvent getEventType() {
    return eventType;
  }

  public String getReason() {
    return reason;
  }

  public String getTraceId() {
    return traceId;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
