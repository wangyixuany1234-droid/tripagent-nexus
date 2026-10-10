package com.tripagent.nexus.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStatus;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStrategy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TravelPlan(
    UUID planId,
    UUID planFamilyId,
    UUID tripId,
    @JsonInclude(JsonInclude.Include.NON_NULL) UUID previousPlanId,
    String alternativeKey,
    PlanStrategy strategy,
    int version,
    String title,
    String summary,
    List<DayPlan> days,
    BudgetSummary budgetSummary,
    List<Conflict> conflicts,
    List<Evidence> evidence,
    String triggerReason,
    ReplanInputSnapshot replanInput,
    String changeSummary,
    boolean autoReplanned,
    PlanStatus status,
    Instant createdAt,
    Instant updatedAt) {}
