package com.tripagent.nexus.application.planning;

import com.tripagent.nexus.domain.model.BudgetSummary;
import com.tripagent.nexus.domain.model.Conflict;
import com.tripagent.nexus.domain.model.DayPlan;
import com.tripagent.nexus.domain.model.DomainEnums.ItemType;
import com.tripagent.nexus.domain.model.DomainEnums.Pace;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStrategy;
import com.tripagent.nexus.domain.model.Evidence;
import com.tripagent.nexus.domain.model.TravelRequirement;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlanningModels {

  private PlanningModels() {}

  public record PlanCandidate(
      String alternativeKey,
      PlanStrategy strategy,
      String title,
      String summary,
      List<DayPlan> days,
      BudgetSummary budgetSummary,
      List<Conflict> semanticConflicts,
      List<Evidence> evidence) {

    public PlanCandidate {
      days = List.copyOf(days);
      semanticConflicts = List.copyOf(semanticConflicts);
      evidence = List.copyOf(evidence);
    }

    public PlanCandidate withDaysAndBudget(
        List<DayPlan> updatedDays, BudgetSummary updatedBudget, String updatedSummary) {
      return new PlanCandidate(
          alternativeKey,
          strategy,
          title,
          updatedSummary,
          updatedDays,
          updatedBudget,
          semanticConflicts,
          evidence);
    }
  }

  public record OperatingWindow(LocalTime opensAt, LocalTime closesAt) {}

  public record AssembledCandidate(PlanCandidate candidate, RuleContext ruleContext) {}

  public record RuleContext(
      TravelRequirement requirement,
      ZoneId zoneId,
      Instant cityArrival,
      Instant cityDeparture,
      Map<UUID, OperatingWindow> operatingWindows,
      Map<UUID, Integer> requiredCommuteMinutes,
      Map<String, BigDecimal> categoryLimits,
      Set<ItemType> confirmedBookingTypes,
      Map<UUID, String> itemAreas,
      Pace pace) {

    public RuleContext {
      operatingWindows = Map.copyOf(operatingWindows);
      requiredCommuteMinutes = Map.copyOf(requiredCommuteMinutes);
      categoryLimits = Map.copyOf(categoryLimits);
      confirmedBookingTypes = Set.copyOf(confirmedBookingTypes);
      itemAreas = Map.copyOf(itemAreas);
    }
  }
}
