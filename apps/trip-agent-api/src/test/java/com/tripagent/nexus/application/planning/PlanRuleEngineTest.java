package com.tripagent.nexus.application.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.tripagent.nexus.application.planning.PlanningModels.OperatingWindow;
import com.tripagent.nexus.application.planning.PlanningModels.PlanCandidate;
import com.tripagent.nexus.application.planning.PlanningModels.RuleContext;
import com.tripagent.nexus.domain.model.BudgetItem;
import com.tripagent.nexus.domain.model.BudgetSummary;
import com.tripagent.nexus.domain.model.Conflict;
import com.tripagent.nexus.domain.model.DayPlan;
import com.tripagent.nexus.domain.model.DomainEnums.ItemType;
import com.tripagent.nexus.domain.model.DomainEnums.Pace;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStrategy;
import com.tripagent.nexus.domain.model.ItineraryItem;
import com.tripagent.nexus.domain.model.Place;
import com.tripagent.nexus.domain.model.TravelRequirement;
import com.tripagent.nexus.domain.model.Traveler;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanRuleEngineTest {

  private final PlanRuleEngine ruleEngine = new PlanRuleEngine();

  @Test
  void detectsEveryDeterministicRuleFamily() {
    LocalDate date = LocalDate.of(2026, 10, 1);
    ItineraryItem transport =
        item(
            "transport",
            ItemType.TRANSPORT,
            "2026-10-01T02:00:00Z",
            "2026-10-01T03:00:00Z",
            "A",
            "3000.0000",
            "CNY",
            List.of());
    ItineraryItem hotel =
        item(
            "hotel",
            ItemType.ACCOMMODATION,
            "2026-10-01T02:30:00Z",
            "2026-10-01T03:30:00Z",
            "A",
            "2800.0000",
            "CNY",
            List.of());
    ItineraryItem attraction =
        item(
            "attraction",
            ItemType.ATTRACTION,
            "2026-10-01T03:15:00Z",
            "2026-10-01T10:30:00Z",
            "A",
            "300.0000",
            "USD",
            List.of());
    ItineraryItem dining =
        item(
            "dining",
            ItemType.DINING,
            "2026-10-01T11:00:00Z",
            "2026-10-01T12:00:00Z",
            "B",
            "900.0000",
            "CNY",
            List.of());
    ItineraryItem returnVisit =
        item(
            "return",
            ItemType.ATTRACTION,
            "2026-10-01T12:15:00Z",
            "2026-10-01T13:00:00Z",
            "A",
            "100.0000",
            "CNY",
            List.of());
    ItineraryItem extra =
        item(
            "extra",
            ItemType.OTHER,
            "2026-10-01T13:15:00Z",
            "2026-10-01T14:00:00Z",
            "A",
            "0.0000",
            "CNY",
            List.of());
    ItineraryItem late =
        item(
            "late",
            ItemType.OTHER,
            "2026-10-01T15:00:00Z",
            "2026-10-01T16:00:00Z",
            "A",
            "0.0000",
            "CNY",
            List.of());
    List<ItineraryItem> items =
        List.of(transport, hotel, attraction, dining, returnVisit, extra, late);
    BudgetSummary budget =
        new BudgetSummary(
            new BigDecimal("5000.0000"),
            new BigDecimal("7100.0000"),
            new BigDecimal("-2100.0000"),
            "CNY",
            items.stream()
                .map(
                    item ->
                        new BudgetItem(
                            item.type().name(), item.title(), item.amount(), item.currency()))
                .toList(),
            List.of(new BudgetSummary.DailyBudget(date, new BigDecimal("6800.0000"), "CNY")));
    PlanCandidate candidate =
        new PlanCandidate(
            "balanced",
            PlanStrategy.BALANCED,
            "Beijing",
            "Rule test",
            List.of(new DayPlan(1, date, "Dense day", items)),
            budget,
            List.of(),
            List.of());
    TravelRequirement requirement =
        new TravelRequirement(
            UUID.randomUUID(),
            new Place(null, "Shanghai", null, null, null),
            List.of(new Place(null, "Beijing", null, null, null)),
            date,
            date.plusDays(2),
            List.of(
                new Traveler(
                    UUID.randomUUID(),
                    "Traveler",
                    com.tripagent.nexus.domain.model.DomainEnums.TravelerType.ADULT,
                    List.of())),
            new BigDecimal("5000.0000"),
            "CNY",
            List.of(),
            Pace.RELAXED,
            List.of(),
            List.of(),
            "",
            Instant.parse("2026-01-01T00:00:00Z"));
    RuleContext context =
        new RuleContext(
            requirement,
            ZoneId.of("Asia/Shanghai"),
            Instant.parse("2026-10-01T04:00:00Z"),
            Instant.parse("2026-10-01T15:30:00Z"),
            Map.of(
                attraction.itemId(), new OperatingWindow(LocalTime.of(9, 0), LocalTime.of(17, 0))),
            Map.of(attraction.itemId(), 60, dining.itemId(), 60),
            Map.of("TRANSPORT", new BigDecimal("1000.0000"), "DINING", new BigDecimal("500.0000")),
            Set.of(),
            Map.of(
                attraction.itemId(), "A",
                dining.itemId(), "B",
                returnVisit.itemId(), "A",
                extra.itemId(), "A",
                late.itemId(), "A",
                transport.itemId(), "A",
                hotel.itemId(), "A"),
            Pace.RELAXED);

    assertThat(ruleEngine.validate(candidate, context))
        .extracting(Conflict::code)
        .contains(
            "ITINERARY_TIME_OVERLAP",
            "ATTRACTION_OUTSIDE_OPENING_HOURS",
            "COMMUTE_TIME_INSUFFICIENT",
            "DAILY_ACTIVITY_DENSITY_EXCEEDED",
            "HOTEL_CHECKIN_TIME_CONFLICT",
            "ACTIVITY_BEFORE_CITY_ARRIVAL",
            "ACTIVITY_AFTER_CITY_DEPARTURE",
            "TOTAL_BUDGET_EXCEEDED",
            "CATEGORY_BUDGET_EXCEEDED",
            "CURRENCY_NOT_CONVERTED",
            "KEY_BOOKING_MISSING",
            "RECOMMENDATION_EVIDENCE_MISSING",
            "ROUTE_OBVIOUS_BACKTRACK");
  }

  private static ItineraryItem item(
      String key,
      ItemType type,
      String start,
      String end,
      String area,
      String amount,
      String currency,
      List<UUID> evidenceIds) {
    return new ItineraryItem(
        UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        type,
        key,
        key,
        Instant.parse(start),
        Instant.parse(end),
        new Place(null, key, area, null, null),
        new BigDecimal(amount),
        currency,
        evidenceIds);
  }
}
