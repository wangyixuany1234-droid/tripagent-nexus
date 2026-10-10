package com.tripagent.nexus.infrastructure.persistence.entity;

import com.tripagent.nexus.domain.model.DomainEnums.GuardedActionStatus;
import com.tripagent.nexus.domain.model.DomainEnums.GuardedActionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "guarded_action")
public class GuardedActionEntity {

  @Id
  @Column(name = "action_id", nullable = false)
  private UUID actionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "action_type", nullable = false, length = 32)
  private GuardedActionType type;

  @Column(name = "resource_id", nullable = false)
  private UUID resourceId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private GuardedActionStatus status;

  @Column(name = "risk_description", nullable = false, length = 1000)
  private String riskDescription;

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal amount;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "proposed_by", nullable = false, length = 128)
  private String proposedBy;

  @Column(name = "confirmed_by", length = 128)
  private String confirmedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "entity_version", nullable = false)
  private long entityVersion;

  protected GuardedActionEntity() {}

  public GuardedActionEntity(
      UUID actionId,
      GuardedActionType type,
      UUID resourceId,
      String riskDescription,
      BigDecimal amount,
      String currency,
      Instant expiresAt,
      String proposedBy,
      Instant now) {
    this.actionId = actionId;
    this.type = type;
    this.resourceId = resourceId;
    this.status = GuardedActionStatus.PENDING_CONFIRMATION;
    this.riskDescription = riskDescription;
    this.amount = amount;
    this.currency = currency;
    this.expiresAt = expiresAt;
    this.proposedBy = proposedBy;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void confirm(String actorId, Instant now) {
    this.status = GuardedActionStatus.CONFIRMED;
    this.confirmedBy = actorId;
    this.updatedAt = now;
  }

  public void reject(Instant now) {
    this.status = GuardedActionStatus.REJECTED;
    this.updatedAt = now;
  }

  public void expire(Instant now) {
    this.status = GuardedActionStatus.EXPIRED;
    this.updatedAt = now;
  }

  public void markExecuted(Instant now) {
    this.status = GuardedActionStatus.EXECUTED;
    this.updatedAt = now;
  }

  public UUID getActionId() {
    return actionId;
  }

  public GuardedActionType getType() {
    return type;
  }

  public UUID getResourceId() {
    return resourceId;
  }

  public GuardedActionStatus getStatus() {
    return status;
  }

  public String getRiskDescription() {
    return riskDescription;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public String getProposedBy() {
    return proposedBy;
  }

  public String getConfirmedBy() {
    return confirmedBy;
  }

  public long getEntityVersion() {
    return entityVersion;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
