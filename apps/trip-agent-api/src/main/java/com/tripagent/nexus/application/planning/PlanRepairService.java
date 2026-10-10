package com.tripagent.nexus.application.planning;

import com.tripagent.nexus.application.planning.PlanningModels.OperatingWindow;
import com.tripagent.nexus.application.planning.PlanningModels.PlanCandidate;
import com.tripagent.nexus.application.planning.PlanningModels.RuleContext;
import com.tripagent.nexus.domain.model.BudgetItem;
import com.tripagent.nexus.domain.model.BudgetSummary;
import com.tripagent.nexus.domain.model.BudgetSummary.DailyBudget;
import com.tripagent.nexus.domain.model.Conflict;
import com.tripagent.nexus.domain.model.DayPlan;
import com.tripagent.nexus.domain.model.ItineraryItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PlanRepairService {

  public RepairResult repair(
      PlanCandidate candidate,
      RuleContext context,
      List<Conflict> conflicts,
      Set<UUID> lockedItemIds,
      Set<UUID> requestedRemovals,
      BigDecimal adjustedBudget,
      String reason) {
    Set<UUID> changed = new LinkedHashSet<>();
    Set<UUID> removed = new LinkedHashSet<>();
    List<DayPlan> days =
        candidate.days().stream()
            .map(
                day ->
                    new DayPlan(
                        day.dayNumber(),
                        day.date(),
                        day.title(),
                        day.items().stream()
                            .filter(
                                item -> {
                                  boolean remove =
                                      requestedRemovals.contains(item.itemId())
                                          && !lockedItemIds.contains(item.itemId());
                                  if (remove) {
                                    removed.add(item.itemId());
                                  }
                                  return !remove;
                                })
                            .toList()))
            .toList();

    for (Conflict conflict : conflicts) {
      if (!conflict.autoFixable() || conflict.relatedItemIds().isEmpty()) {
        continue;
      }
      UUID targetId = conflict.relatedItemIds().getLast();
      if (lockedItemIds.contains(targetId)) {
        continue;
      }
      days =
          switch (conflict.code()) {
            case "COMMUTE_TIME_INSUFFICIENT", "ITINERARY_TIME_OVERLAP" ->
                moveAfterPrevious(days, targetId, context, changed);
            case "ATTRACTION_OUTSIDE_OPENING_HOURS" ->
                moveIntoOpeningWindow(days, targetId, context, changed);
            case "ACTIVITY_BEFORE_CITY_ARRIVAL" ->
                moveTo(days, targetId, context.cityArrival(), changed);
            case "ACTIVITY_AFTER_CITY_DEPARTURE" ->
                moveBefore(days, targetId, context.cityDeparture(), changed);
            case "DAILY_ACTIVITY_DENSITY_EXCEEDED", "ROUTE_OBVIOUS_BACKTRACK" ->
                removeOptional(days, targetId, removed);
            default -> days;
          };
    }

    BudgetSummary budget =
        recalculateBudget(
            days,
            adjustedBudget == null ? candidate.budgetSummary().totalBudget() : adjustedBudget,
            candidate.budgetSummary().currency());
    String summary =
        candidate.summary()
            + " Replanned locally: "
            + reason
            + "; changed="
            + changed.size()
            + ", removed="
            + removed.size()
            + ".";
    return new RepairResult(
        candidate.withDaysAndBudget(days, budget, summary),
        List.copyOf(changed),
        List.copyOf(removed),
        "Local replan changed "
            + changed.size()
            + " item(s) and removed "
            + removed.size()
            + " item(s).");
  }

  private static List<DayPlan> moveAfterPrevious(
      List<DayPlan> days, UUID targetId, RuleContext context, Set<UUID> changed) {
    List<DayPlan> updated = new ArrayList<>();
    for (DayPlan day : days) {
      List<ItineraryItem> ordered =
          day.items().stream().sorted(Comparator.comparing(ItineraryItem::startTime)).toList();
      List<ItineraryItem> items = new ArrayList<>(ordered);
      for (int index = 1; index < items.size(); index++) {
        ItineraryItem current = items.get(index);
        if (!current.itemId().equals(targetId)) {
          continue;
        }
        ItineraryItem previous = items.get(index - 1);
        int commute = context.requiredCommuteMinutes().getOrDefault(targetId, 0);
        Instant start = previous.endTime().plus(Duration.ofMinutes(commute));
        items.set(index, move(current, start));
        changed.add(targetId);
      }
      updated.add(new DayPlan(day.dayNumber(), day.date(), day.title(), List.copyOf(items)));
    }
    return List.copyOf(updated);
  }

  private static List<DayPlan> moveIntoOpeningWindow(
      List<DayPlan> days, UUID targetId, RuleContext context, Set<UUID> changed) {
    OperatingWindow window = context.operatingWindows().get(targetId);
    if (window == null) {
      return days;
    }
    return mapItem(
        days,
        targetId,
        item -> {
          Instant start =
              item.startTime()
                  .atZone(context.zoneId())
                  .toLocalDate()
                  .atTime(window.opensAt())
                  .atZone(context.zoneId())
                  .toInstant();
          changed.add(targetId);
          return move(item, start);
        });
  }

  private static List<DayPlan> moveTo(
      List<DayPlan> days, UUID targetId, Instant start, Set<UUID> changed) {
    return mapItem(
        days,
        targetId,
        item -> {
          changed.add(targetId);
          return move(item, start);
        });
  }

  private static List<DayPlan> moveBefore(
      List<DayPlan> days, UUID targetId, Instant end, Set<UUID> changed) {
    return mapItem(
        days,
        targetId,
        item -> {
          changed.add(targetId);
          return move(item, end.minus(Duration.between(item.startTime(), item.endTime())));
        });
  }

  private static List<DayPlan> removeOptional(
      List<DayPlan> days, UUID targetId, Set<UUID> removed) {
    return days.stream()
        .map(
            day ->
                new DayPlan(
                    day.dayNumber(),
                    day.date(),
                    day.title(),
                    day.items().stream()
                        .filter(
                            item -> {
                              boolean keep = !item.itemId().equals(targetId);
                              if (!keep) {
                                removed.add(targetId);
                              }
                              return keep;
                            })
                        .toList()))
        .toList();
  }

  private static List<DayPlan> mapItem(
      List<DayPlan> days, UUID targetId, java.util.function.UnaryOperator<ItineraryItem> mapper) {
    return days.stream()
        .map(
            day ->
                new DayPlan(
                    day.dayNumber(),
                    day.date(),
                    day.title(),
                    day.items().stream()
                        .map(item -> item.itemId().equals(targetId) ? mapper.apply(item) : item)
                        .sorted(Comparator.comparing(ItineraryItem::startTime))
                        .toList()))
        .toList();
  }

  private static ItineraryItem move(ItineraryItem item, Instant newStart) {
    Duration duration = Duration.between(item.startTime(), item.endTime());
    return new ItineraryItem(
        item.itemId(),
        item.type(),
        item.title(),
        item.description(),
        newStart,
        newStart.plus(duration),
        item.location(),
        item.amount(),
        item.currency(),
        item.evidenceIds());
  }

  private static BudgetSummary recalculateBudget(
      List<DayPlan> days, BigDecimal totalBudget, String currency) {
    Map<LocalDate, BigDecimal> daily = new LinkedHashMap<>();
    List<BudgetItem> items = new ArrayList<>();
    for (DayPlan day : days) {
      BigDecimal total = BigDecimal.ZERO;
      for (ItineraryItem item : day.items()) {
        items.add(
            new BudgetItem(
                item.type() == com.tripagent.nexus.domain.model.DomainEnums.ItemType.FREE_TIME
                    ? "OTHER"
                    : item.type().name(),
                item.title(),
                item.amount(),
                item.currency()));
        if (currency.equals(item.currency())) {
          total = total.add(item.amount());
        }
      }
      daily.put(day.date(), total.setScale(4, RoundingMode.HALF_UP));
    }
    BigDecimal estimated =
        items.stream()
            .filter(item -> currency.equals(item.currency()))
            .map(BudgetItem::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(4, RoundingMode.HALF_UP);
    List<DailyBudget> dailyBudgets =
        daily.entrySet().stream()
            .map(entry -> new DailyBudget(entry.getKey(), entry.getValue(), currency))
            .toList();
    return new BudgetSummary(
        totalBudget.setScale(4, RoundingMode.HALF_UP),
        estimated,
        totalBudget.subtract(estimated).setScale(4, RoundingMode.HALF_UP),
        currency,
        List.copyOf(items),
        dailyBudgets);
  }

  public record RepairResult(
      PlanCandidate candidate,
      List<UUID> changedItemIds,
      List<UUID> removedItemIds,
      String changeSummary) {}
}
