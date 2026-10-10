package com.tripagent.nexus.infrastructure.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStatus;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStrategy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "travel_plan")
public class TravelPlanEntity {

  @Id
  @Column(name = "plan_id", nullable = false)
  private UUID planId;

  @Column(name = "trip_id", nullable = false)
  private UUID tripId;

  @Column(name = "plan_family_id", nullable = false)
  private UUID planFamilyId;

  @Column(name = "previous_plan_id")
  private UUID previousPlanId;

  @Column(name = "alternative_key", nullable = false, length = 40)
  private String alternativeKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private PlanStrategy strategy;

  @Column(nullable = false)
  private int version;

  @Column(nullable = false, length = 200)
  private String title;

  @Column(nullable = false, length = 5000)
  private String summary;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode days;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "budget_summary", nullable = false, columnDefinition = "jsonb")
  private JsonNode budgetSummary;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode conflicts;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode evidence;

  @Column(name = "trigger_reason", nullable = false, length = 1000)
  private String triggerReason;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "replan_input", nullable = false, columnDefinition = "jsonb")
  private JsonNode replanInput;

  @Column(name = "change_summary", nullable = false, length = 2000)
  private String changeSummary;

  @Column(name = "auto_replanned", nullable = false)
  private boolean autoReplanned;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private PlanStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "confirmed_at")
  private Instant confirmedAt;

  @Version
  @Column(name = "entity_version", nullable = false)
  private long entityVersion;

  protected TravelPlanEntity() {}

  public TravelPlanEntity(
      UUID planId,
      UUID tripId,
      UUID planFamilyId,
      UUID previousPlanId,
      String alternativeKey,
      PlanStrategy strategy,
      int version,
      String title,
      String summary,
      JsonNode days,
      JsonNode budgetSummary,
      JsonNode conflicts,
      JsonNode evidence,
      String triggerReason,
      JsonNode replanInput,
      String changeSummary,
      boolean autoReplanned,
      Instant now) {
    this.planId = planId;
    this.tripId = tripId;
    this.planFamilyId = planFamilyId;
    this.previousPlanId = previousPlanId;
    this.alternativeKey = alternativeKey;
    this.strategy = strategy;
    this.version = version;
    this.title = title;
    this.summary = summary;
    this.days = days;
    this.budgetSummary = budgetSummary;
    this.conflicts = conflicts;
    this.evidence = evidence;
    this.triggerReason = triggerReason;
    this.replanInput = replanInput;
    this.changeSummary = changeSummary;
    this.autoReplanned = autoReplanned;
    this.status = PlanStatus.GENERATING;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void markReady(String summary, Instant now) {
    requireGenerating();
    this.summary = summary;
    this.status = PlanStatus.READY;
    this.updatedAt = now;
  }

  public void complete(
      String summary,
      JsonNode days,
      JsonNode budgetSummary,
      JsonNode conflicts,
      JsonNode evidence,
      PlanStatus status,
      String changeSummary,
      Instant now) {
    requireGenerating();
    if (status != PlanStatus.READY && status != PlanStatus.PARTIAL) {
      throw new IllegalArgumentException("PLAN_COMPLETION_STATUS_INVALID");
    }
    this.summary = summary;
    this.days = days;
    this.budgetSummary = budgetSummary;
    this.conflicts = conflicts;
    this.evidence = evidence;
    this.status = status;
    this.changeSummary = changeSummary;
    this.updatedAt = now;
  }

  public void markFailed(String summary, Instant now) {
    if (status != PlanStatus.GENERATING) {
      return;
    }
    this.summary = summary;
    this.status = PlanStatus.FAILED;
    this.updatedAt = now;
  }

  public void confirm(int expectedVersion, Instant now) {
    if (version != expectedVersion) {
      throw new IllegalStateException("PLAN_VERSION_CONFLICT");
    }
    if (status != PlanStatus.READY && status != PlanStatus.PARTIAL) {
      throw new IllegalStateException("PLAN_NOT_CONFIRMABLE");
    }
    this.status = PlanStatus.CONFIRMED;
    this.confirmedAt = now;
    this.updatedAt = now;
  }

  public void cancel(Instant now) {
    requireGenerating();
    this.status = PlanStatus.CANCELLED;
    this.updatedAt = now;
  }

  private void requireGenerating() {
    if (status != PlanStatus.GENERATING) {
      throw new IllegalStateException("PLAN_ALREADY_TERMINAL");
    }
  }

  public UUID getPlanId() {
    return planId;
  }

  public UUID getTripId() {
    return tripId;
  }

  public UUID getPlanFamilyId() {
    return planFamilyId;
  }

  public UUID getPreviousPlanId() {
    return previousPlanId;
  }

  public String getAlternativeKey() {
    return alternativeKey;
  }

  public PlanStrategy getStrategy() {
    return strategy;
  }

  public int getVersion() {
    return version;
  }

  public String getTitle() {
    return title;
  }

  public String getSummary() {
    return summary;
  }

  public JsonNode getDays() {
    return days;
  }

  public JsonNode getBudgetSummary() {
    return budgetSummary;
  }

  public JsonNode getConflicts() {
    return conflicts;
  }

  public JsonNode getEvidence() {
    return evidence;
  }

  public String getTriggerReason() {
    return triggerReason;
  }

  public JsonNode getReplanInput() {
    return replanInput;
  }

  public String getChangeSummary() {
    return changeSummary;
  }

  public boolean isAutoReplanned() {
    return autoReplanned;
  }

  public PlanStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
