package com.tripagent.nexus.application.planning;

import com.tripagent.nexus.application.planning.PlanningModels.OperatingWindow;
import com.tripagent.nexus.application.planning.PlanningModels.PlanCandidate;
import com.tripagent.nexus.application.planning.PlanningModels.RuleContext;
import com.tripagent.nexus.domain.model.BudgetItem;
import com.tripagent.nexus.domain.model.Conflict;
import com.tripagent.nexus.domain.model.DayPlan;
import com.tripagent.nexus.domain.model.DomainEnums.ConflictStatus;
import com.tripagent.nexus.domain.model.DomainEnums.ConflictType;
import com.tripagent.nexus.domain.model.DomainEnums.ItemType;
import com.tripagent.nexus.domain.model.DomainEnums.Pace;
import com.tripagent.nexus.domain.model.DomainEnums.Severity;
import com.tripagent.nexus.domain.model.ItineraryItem;
import com.tripagent.nexus.observability.TraceOperations;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PlanRuleEngine {

  public static final String VERSION = "plan-rules-v1.0.0";

  private static final Set<ItemType> LOCAL_ACTIVITY_TYPES =
      EnumSet.of(ItemType.ATTRACTION, ItemType.DINING, ItemType.FREE_TIME, ItemType.OTHER);

  private TraceOperations traces;

  @Autowired(required = false)
  void setTraceOperations(TraceOperations traces) {
    this.traces = traces;
  }

  public List<Conflict> validate(PlanCandidate candidate, RuleContext context) {
    if (traces == null) {
      return validateRules(candidate, context);
    }
    return traces.trace(
        "rule.validation",
        "tripagent.rule.validation",
        Map.of("rule.engine.version", VERSION),
        () -> validateRules(candidate, context));
  }

  private List<Conflict> validateRules(PlanCandidate candidate, RuleContext context) {
    List<Conflict> conflicts = new ArrayList<>(candidate.semanticConflicts());
    validateDays(candidate, context, conflicts);
    validateBudget(candidate, context, conflicts);
    validateBookings(context, conflicts);
    return List.copyOf(conflicts);
  }

  private void validateDays(
      PlanCandidate candidate, RuleContext context, List<Conflict> conflicts) {
    List<DayPlan> days = candidate.days();
    Set<UUID> evidenceIds =
        candidate.evidence().stream()
            .map(com.tripagent.nexus.domain.model.Evidence::evidenceId)
            .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    for (int dayIndex = 0; dayIndex < days.size(); dayIndex++) {
      DayPlan day = days.get(dayIndex);
      List<IndexedItem> items = indexed(day, dayIndex);
      validateTimeRanges(items, conflicts);
      validateOverlaps(items, conflicts);
      validateOpeningHours(items, context, conflicts);
      validateCommute(items, context, conflicts);
      validateDensity(items, context.pace(), dayIndex, conflicts);
      validateCityWindow(items, context, conflicts);
      validateHotelCheckIn(items, context, conflicts);
      validateBacktracking(items, context, conflicts);
      validateRecommendationEvidence(items, evidenceIds, conflicts);
    }
  }

  private void validateTimeRanges(List<IndexedItem> items, List<Conflict> conflicts) {
    items.stream()
        .filter(item -> !item.item().endTime().isAfter(item.item().startTime()))
        .forEach(
            item ->
                conflicts.add(
                    conflict(
                        "ITINERARY_TIME_RANGE_INVALID",
                        ConflictType.TIME,
                        Severity.ERROR,
                        "An itinerary item must end after it starts.",
                        item.path(),
                        List.of(item.item().itemId()),
                        true)));
  }

  private void validateOverlaps(List<IndexedItem> items, List<Conflict> conflicts) {
    List<IndexedItem> ordered =
        items.stream().sorted(Comparator.comparing(item -> item.item().startTime())).toList();
    for (int index = 1; index < ordered.size(); index++) {
      IndexedItem previous = ordered.get(index - 1);
      IndexedItem current = ordered.get(index);
      if (current.item().startTime().isBefore(previous.item().endTime())) {
        conflicts.add(
            conflict(
                "ITINERARY_TIME_OVERLAP",
                ConflictType.TIME,
                Severity.ERROR,
                "Two itinerary items overlap.",
                current.path(),
                List.of(previous.item().itemId(), current.item().itemId()),
                true));
      }
    }
  }

  private void validateOpeningHours(
      List<IndexedItem> items, RuleContext context, List<Conflict> conflicts) {
    for (IndexedItem indexed : items) {
      OperatingWindow window = context.operatingWindows().get(indexed.item().itemId());
      if (window == null) {
        continue;
      }
      LocalTime arrival = indexed.item().startTime().atZone(context.zoneId()).toLocalTime();
      LocalTime departure = indexed.item().endTime().atZone(context.zoneId()).toLocalTime();
      if (arrival.isBefore(window.opensAt()) || departure.isAfter(window.closesAt())) {
        conflicts.add(
            conflict(
                "ATTRACTION_OUTSIDE_OPENING_HOURS",
                ConflictType.AVAILABILITY,
                Severity.ERROR,
                "The attraction visit is outside its opening hours.",
                indexed.path(),
                List.of(indexed.item().itemId()),
                true));
      }
    }
  }

  private void validateCommute(
      List<IndexedItem> items, RuleContext context, List<Conflict> conflicts) {
    List<IndexedItem> ordered =
        items.stream().sorted(Comparator.comparing(item -> item.item().startTime())).toList();
    for (int index = 1; index < ordered.size(); index++) {
      IndexedItem previous = ordered.get(index - 1);
      IndexedItem current = ordered.get(index);
      int required = context.requiredCommuteMinutes().getOrDefault(current.item().itemId(), 0);
      long available =
          Duration.between(previous.item().endTime(), current.item().startTime()).toMinutes();
      if (required > 0 && available >= 0 && available < required) {
        conflicts.add(
            conflict(
                "COMMUTE_TIME_INSUFFICIENT",
                ConflictType.TIME,
                Severity.ERROR,
                "The itinerary does not leave enough time for the required commute.",
                current.path(),
                List.of(previous.item().itemId(), current.item().itemId()),
                true));
      }
    }
  }

  private void validateDensity(
      List<IndexedItem> items, Pace pace, int dayIndex, List<Conflict> conflicts) {
    int limit =
        switch (pace) {
          case RELAXED -> 3;
          case BALANCED -> 4;
          case INTENSIVE -> 6;
        };
    List<UUID> activities =
        items.stream()
            .map(IndexedItem::item)
            .filter(item -> LOCAL_ACTIVITY_TYPES.contains(item.type()))
            .map(ItineraryItem::itemId)
            .toList();
    if (activities.size() > limit) {
      conflicts.add(
          conflict(
              "DAILY_ACTIVITY_DENSITY_EXCEEDED",
              ConflictType.PREFERENCE,
              Severity.WARNING,
              "The day contains more activities than the selected pace allows.",
              "/days/" + dayIndex + "/items",
              activities,
              true));
    }
  }

  private void validateCityWindow(
      List<IndexedItem> items, RuleContext context, List<Conflict> conflicts) {
    for (IndexedItem indexed : items) {
      if (!LOCAL_ACTIVITY_TYPES.contains(indexed.item().type())) {
        continue;
      }
      if (indexed.item().startTime().isBefore(context.cityArrival())) {
        conflicts.add(
            conflict(
                "ACTIVITY_BEFORE_CITY_ARRIVAL",
                ConflictType.TIME,
                Severity.ERROR,
                "A local activity starts before arrival in the destination city.",
                indexed.path(),
                List.of(indexed.item().itemId()),
                true));
      }
      if (indexed.item().endTime().isAfter(context.cityDeparture())) {
        conflicts.add(
            conflict(
                "ACTIVITY_AFTER_CITY_DEPARTURE",
                ConflictType.TIME,
                Severity.ERROR,
                "A local activity ends after departure from the destination city.",
                indexed.path(),
                List.of(indexed.item().itemId()),
                true));
      }
    }
  }

  private void validateHotelCheckIn(
      List<IndexedItem> items, RuleContext context, List<Conflict> conflicts) {
    items.stream()
        .filter(item -> item.item().type() == ItemType.ACCOMMODATION)
        .filter(item -> item.item().startTime().isBefore(context.cityArrival()))
        .forEach(
            item ->
                conflicts.add(
                    conflict(
                        "HOTEL_CHECKIN_TIME_CONFLICT",
                        ConflictType.TIME,
                        Severity.ERROR,
                        "Hotel check-in is scheduled before arrival in the city.",
                        item.path(),
                        List.of(item.item().itemId()),
                        true)));
  }

  private void validateBacktracking(
      List<IndexedItem> items, RuleContext context, List<Conflict> conflicts) {
    List<IndexedItem> local =
        items.stream()
            .filter(item -> LOCAL_ACTIVITY_TYPES.contains(item.item().type()))
            .filter(item -> context.itemAreas().containsKey(item.item().itemId()))
            .toList();
    for (int index = 2; index < local.size(); index++) {
      IndexedItem first = local.get(index - 2);
      IndexedItem middle = local.get(index - 1);
      IndexedItem last = local.get(index);
      String firstArea = context.itemAreas().get(first.item().itemId());
      String middleArea = context.itemAreas().get(middle.item().itemId());
      String lastArea = context.itemAreas().get(last.item().itemId());
      if (firstArea.equals(lastArea) && !firstArea.equals(middleArea)) {
        conflicts.add(
            conflict(
                "ROUTE_OBVIOUS_BACKTRACK",
                ConflictType.LOCATION,
                Severity.WARNING,
                "The route returns to a previously visited area after crossing another area.",
                last.path(),
                List.of(first.item().itemId(), middle.item().itemId(), last.item().itemId()),
                true));
      }
    }
  }

  private void validateRecommendationEvidence(
      List<IndexedItem> items, Set<UUID> availableEvidenceIds, List<Conflict> conflicts) {
    for (IndexedItem item : items) {
      if (item.item().type() != ItemType.ATTRACTION && item.item().type() != ItemType.DINING) {
        continue;
      }
      if (item.item().evidenceIds().isEmpty()) {
        conflicts.add(
            conflict(
                "RECOMMENDATION_EVIDENCE_MISSING",
                ConflictType.EVIDENCE,
                Severity.ERROR,
                "A recommended place has no traceable evidence.",
                item.path(),
                List.of(item.item().itemId()),
                false));
      } else if (!availableEvidenceIds.containsAll(item.item().evidenceIds())) {
        conflicts.add(
            conflict(
                "RECOMMENDATION_EVIDENCE_INVALID",
                ConflictType.EVIDENCE,
                Severity.ERROR,
                "A recommended place references evidence outside the candidate.",
                item.path(),
                List.of(item.item().itemId()),
                false));
      }
    }
  }

  private void validateBudget(
      PlanCandidate candidate, RuleContext context, List<Conflict> conflicts) {
    boolean negativeAmount =
        candidate.budgetSummary().totalBudget().signum() < 0
            || candidate.budgetSummary().estimatedTotal().signum() < 0
            || candidate.budgetSummary().items().stream()
                .anyMatch(item -> item.amount().signum() < 0)
            || candidate.budgetSummary().dailyBudgets().stream()
                .anyMatch(item -> item.amount().signum() < 0)
            || candidate.days().stream()
                .flatMap(day -> day.items().stream())
                .anyMatch(item -> item.amount().signum() < 0);
    if (negativeAmount) {
      conflicts.add(
          conflict(
              "BUDGET_AMOUNT_NEGATIVE",
              ConflictType.BUDGET,
              Severity.ERROR,
              "Budget amounts must not be negative.",
              "/budgetSummary",
              List.of(),
              false));
    }

    BigDecimal itemTotal =
        candidate.budgetSummary().items().stream()
            .filter(item -> candidate.budgetSummary().currency().equals(item.currency()))
            .map(BudgetItem::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal expectedRemaining =
        candidate
            .budgetSummary()
            .totalBudget()
            .subtract(candidate.budgetSummary().estimatedTotal());
    if (itemTotal.compareTo(candidate.budgetSummary().estimatedTotal()) != 0
        || expectedRemaining.compareTo(candidate.budgetSummary().remainingBudget()) != 0) {
      conflicts.add(
          conflict(
              "BUDGET_SUMMARY_MISMATCH",
              ConflictType.BUDGET,
              Severity.ERROR,
              "Budget summary totals do not match the deterministic line-item calculation.",
              "/budgetSummary",
              List.of(),
              false));
    }

    if (candidate
            .budgetSummary()
            .estimatedTotal()
            .compareTo(candidate.budgetSummary().totalBudget())
        > 0) {
      conflicts.add(
          conflict(
              "TOTAL_BUDGET_EXCEEDED",
              ConflictType.BUDGET,
              Severity.ERROR,
              "The estimated total exceeds the configured budget.",
              "/budgetSummary/estimatedTotal",
              List.of(),
              true));
    }

    Map<String, BigDecimal> categoryTotals = new LinkedHashMap<>();
    for (BudgetItem item : candidate.budgetSummary().items()) {
      if (!candidate.budgetSummary().currency().equals(item.currency())) {
        conflicts.add(
            conflict(
                "CURRENCY_NOT_CONVERTED",
                ConflictType.BUDGET,
                Severity.ERROR,
                "A budget item has not been converted to the plan currency.",
                "/budgetSummary/items",
                List.of(),
                false));
      }
      categoryTotals.merge(item.category(), item.amount(), BigDecimal::add);
    }
    categoryTotals.forEach(
        (category, amount) -> {
          BigDecimal limit = context.categoryLimits().get(category);
          if (limit != null && amount.compareTo(limit) > 0) {
            conflicts.add(
                conflict(
                    "CATEGORY_BUDGET_EXCEEDED",
                    ConflictType.BUDGET,
                    Severity.WARNING,
                    "The " + category + " category exceeds its allocated budget.",
                    "/budgetSummary/items",
                    List.of(),
                    true));
          }
        });
  }

  private void validateBookings(RuleContext context, List<Conflict> conflicts) {
    if (!context.confirmedBookingTypes().contains(ItemType.TRANSPORT)) {
      conflicts.add(
          conflict(
              "KEY_BOOKING_MISSING",
              ConflictType.AVAILABILITY,
              Severity.WARNING,
              "No confirmed transport booking is attached to this trip.",
              "/days",
              List.of(),
              false));
    }
    if (!context.confirmedBookingTypes().contains(ItemType.ACCOMMODATION)) {
      conflicts.add(
          conflict(
              "KEY_BOOKING_MISSING",
              ConflictType.AVAILABILITY,
              Severity.WARNING,
              "No confirmed accommodation booking is attached to this trip.",
              "/days",
              List.of(),
              false));
    }
  }

  private static List<IndexedItem> indexed(DayPlan day, int dayIndex) {
    List<IndexedItem> items = new ArrayList<>();
    for (int itemIndex = 0; itemIndex < day.items().size(); itemIndex++) {
      items.add(
          new IndexedItem(day.items().get(itemIndex), "/days/" + dayIndex + "/items/" + itemIndex));
    }
    return items;
  }

  private static Conflict conflict(
      String code,
      ConflictType type,
      Severity severity,
      String message,
      String fieldPath,
      List<UUID> relatedItemIds,
      boolean autoFixable) {
    String key = code + ":" + relatedItemIds + ":" + fieldPath;
    return new Conflict(
        UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
        code,
        type,
        severity,
        message,
        fieldPath,
        List.copyOf(relatedItemIds),
        autoFixable,
        ConflictStatus.OPEN);
  }

  private record IndexedItem(ItineraryItem item, String path) {}
}
