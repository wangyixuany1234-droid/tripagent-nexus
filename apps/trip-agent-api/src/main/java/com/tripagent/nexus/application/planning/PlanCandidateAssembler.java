package com.tripagent.nexus.application.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripagent.nexus.agent.AgentContracts.AgentEvidence;
import com.tripagent.nexus.application.planning.PlanningModels.AssembledCandidate;
import com.tripagent.nexus.application.planning.PlanningModels.OperatingWindow;
import com.tripagent.nexus.application.planning.PlanningModels.PlanCandidate;
import com.tripagent.nexus.application.planning.PlanningModels.RuleContext;
import com.tripagent.nexus.domain.model.BudgetItem;
import com.tripagent.nexus.domain.model.BudgetSummary;
import com.tripagent.nexus.domain.model.BudgetSummary.DailyBudget;
import com.tripagent.nexus.domain.model.Conflict;
import com.tripagent.nexus.domain.model.DayPlan;
import com.tripagent.nexus.domain.model.DomainEnums.BookingReviewStatus;
import com.tripagent.nexus.domain.model.DomainEnums.BookingType;
import com.tripagent.nexus.domain.model.DomainEnums.ConflictStatus;
import com.tripagent.nexus.domain.model.DomainEnums.ConflictType;
import com.tripagent.nexus.domain.model.DomainEnums.EvidenceSourceType;
import com.tripagent.nexus.domain.model.DomainEnums.ItemType;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStrategy;
import com.tripagent.nexus.domain.model.DomainEnums.Severity;
import com.tripagent.nexus.domain.model.Evidence;
import com.tripagent.nexus.domain.model.ItineraryItem;
import com.tripagent.nexus.domain.model.Place;
import com.tripagent.nexus.domain.model.TravelRequirement;
import com.tripagent.nexus.infrastructure.config.PlanningProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.ParsedBookingEntity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PlanCandidateAssembler {

  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4);

  private final PlanningProperties properties;

  public PlanCandidateAssembler(PlanningProperties properties) {
    this.properties = properties;
  }

  public AssembledCandidate assemble(
      UUID planFamilyId,
      String alternativeKey,
      PlanStrategy strategy,
      TravelRequirement requirement,
      ZoneId zoneId,
      JsonNode coordinatorPlan,
      List<AgentEvidence> agentEvidence,
      List<ParsedBookingEntity> bookings,
      BigDecimal adjustedBudget) {
    List<Evidence> evidence = toEvidence(agentEvidence, bookings);
    UUID publicEvidenceId =
        evidence.stream()
            .filter(item -> item.sourceType() != EvidenceSourceType.USER_MEMORY)
            .map(Evidence::evidenceId)
            .findFirst()
            .orElse(null);
    UUID memoryEvidenceId =
        evidence.stream()
            .filter(item -> item.sourceType() == EvidenceSourceType.USER_MEMORY)
            .map(Evidence::evidenceId)
            .findFirst()
            .orElse(publicEvidenceId);

    String city = requirement.destinations().getFirst().name();
    Instant generatedArrival = transportArrival(coordinatorPlan, requirement, zoneId);
    Instant cityArrival = confirmedArrival(bookings, city).orElse(generatedArrival);
    Instant cityDeparture = requirement.endDate().atTime(21, 0).atZone(zoneId).toInstant();
    List<ItineraryItem> fixedItems =
        bookings.stream()
            .filter(PlanCandidateAssembler::confirmed)
            .filter(booking -> booking.getStartTime() != null && booking.getEndTime() != null)
            .map(
                booking ->
                    bookingItem(planFamilyId, alternativeKey, booking, city, publicEvidenceId))
            .toList();

    List<DayPlan> days =
        buildDays(
            planFamilyId,
            alternativeKey,
            strategy,
            requirement,
            zoneId,
            coordinatorPlan,
            fixedItems,
            cityArrival,
            publicEvidenceId,
            memoryEvidenceId);
    BigDecimal budgetLimit = adjustedBudget == null ? requirement.totalBudget() : adjustedBudget;
    BudgetSummary budget = budget(days, budgetLimit, requirement.currency());
    List<Conflict> semanticConflicts = semanticConflicts(coordinatorPlan);
    PlanCandidate candidate =
        new PlanCandidate(
            alternativeKey,
            strategy,
            title(strategy, city),
            summary(strategy, city),
            days,
            budget,
            semanticConflicts,
            evidence);
    RuleContext context =
        new RuleContext(
            requirement,
            zoneId,
            cityArrival,
            cityDeparture,
            operatingWindows(days),
            commuteRequirements(days, strategy),
            categoryLimits(budgetLimit),
            confirmedTypes(bookings),
            itemAreas(days),
            requirement.pace());
    return new AssembledCandidate(candidate, context);
  }

  private List<DayPlan> buildDays(
      UUID familyId,
      String alternativeKey,
      PlanStrategy strategy,
      TravelRequirement requirement,
      ZoneId zoneId,
      JsonNode plan,
      List<ItineraryItem> fixedItems,
      Instant cityArrival,
      UUID publicEvidenceId,
      UUID memoryEvidenceId) {
    Map<LocalDate, List<ItineraryItem>> itemsByDate = new LinkedHashMap<>();
    for (LocalDate date = requirement.startDate();
        !date.isAfter(requirement.endDate());
        date = date.plusDays(1)) {
      itemsByDate.put(date, new ArrayList<>());
    }
    fixedItems.forEach(
        item ->
            itemsByDate
                .computeIfAbsent(
                    item.startTime().atZone(zoneId).toLocalDate(), ignored -> new ArrayList<>())
                .add(item));

    boolean hasTransport = fixedItems.stream().anyMatch(item -> item.type() == ItemType.TRANSPORT);
    boolean hasAccommodation =
        fixedItems.stream().anyMatch(item -> item.type() == ItemType.ACCOMMODATION);
    LocalDate firstDate = requirement.startDate();
    if (!hasTransport) {
      JsonNode option = plan.path("transport").path("options").path(0);
      Instant departure =
          parseInstant(
              option.path("departureTime").asText(),
              firstDate.atTime(6, 0).atZone(zoneId).toInstant());
      Instant arrival = parseInstant(option.path("arrivalTime").asText(), cityArrival);
      itemsByDate
          .get(firstDate)
          .add(
              item(
                  familyId,
                  alternativeKey,
                  "transport",
                  ItemType.TRANSPORT,
                  option.path("mode").asText("Transport") + " to " + destination(requirement),
                  "Generated intercity transport candidate.",
                  departure,
                  arrival,
                  option.path("destination").asText(destination(requirement)),
                  "Intercity",
                  decimal(option, "cost", "0.0000"),
                  option.path("currency").asText(requirement.currency()),
                  List.of()));
    }

    if (!hasAccommodation) {
      JsonNode stay = plan.path("accommodation").path("options").path(0);
      ZonedDateTime checkIn = firstDate.atTime(15, 30).atZone(zoneId).withLaterOffsetAtOverlap();
      itemsByDate
          .get(firstDate)
          .add(
              item(
                  familyId,
                  alternativeKey,
                  "accommodation",
                  ItemType.ACCOMMODATION,
                  stay.path("name").asText("Selected hotel"),
                  "Hotel check-in in " + stay.path("area").asText("central area") + ".",
                  checkIn.toInstant(),
                  checkIn.plusMinutes(30).toInstant(),
                  stay.path("name").asText("Selected hotel"),
                  stay.path("area").asText("Central"),
                  decimal(stay, "cost", "0.0000"),
                  stay.path("currency").asText(requirement.currency()),
                  List.of()));
    }

    JsonNode recommendations = plan.path("recommendations").path("recommendations");
    JsonNode attraction = firstOfType(recommendations, "ATTRACTION");
    JsonNode restaurant = firstOfType(recommendations, "RESTAURANT");
    LocalDate attractionDate =
        strategy == PlanStrategy.LOW_TRANSIT && itemsByDate.size() > 1
            ? firstDate.plusDays(1)
            : firstDate;
    Instant attractionStart =
        strategy == PlanStrategy.BALANCED
            ? cityArrival.plus(Duration.ofMinutes(30))
            : attractionDate.atTime(10, 0).atZone(zoneId).toInstant();
    itemsByDate
        .get(attractionDate)
        .add(
            item(
                familyId,
                alternativeKey,
                "attraction",
                ItemType.ATTRACTION,
                attraction.path("name").asText("Historic attraction"),
                "Evidence-backed historical and cultural visit.",
                attractionStart,
                attractionStart.plus(Duration.ofHours(3)),
                attraction.path("name").asText("Historic attraction"),
                attraction.path("area").asText("Dongcheng"),
                decimalOrNull(attraction, "latitude"),
                decimalOrNull(attraction, "longitude"),
                decimal(attraction, "estimatedCost", "0.0000"),
                attraction.path("currency").asText(requirement.currency()),
                ids(publicEvidenceId)));

    LocalDate diningDate =
        strategy == PlanStrategy.LOW_TRANSIT ? attractionDate : firstDate.plusDays(1);
    Instant diningStart = diningDate.atTime(18, 0).atZone(zoneId).toInstant();
    itemsByDate
        .get(diningDate)
        .add(
            item(
                familyId,
                alternativeKey,
                "dining",
                ItemType.DINING,
                restaurant.path("name").asText("Local restaurant"),
                "Local dining selected from public knowledge and user preference.",
                diningStart,
                diningStart.plus(Duration.ofMinutes(90)),
                restaurant.path("name").asText("Local restaurant"),
                restaurant.path("area").asText("Dongcheng"),
                decimalOrNull(restaurant, "latitude"),
                decimalOrNull(restaurant, "longitude"),
                decimal(restaurant, "estimatedCost", "0.0000"),
                restaurant.path("currency").asText(requirement.currency()),
                ids(memoryEvidenceId)));

    LocalDate finalDate = requirement.endDate();
    Instant freeStart = finalDate.atTime(9, 30).atZone(zoneId).toInstant();
    itemsByDate
        .get(finalDate)
        .add(
            item(
                familyId,
                alternativeKey,
                "free-time",
                ItemType.FREE_TIME,
                strategy == PlanStrategy.LOW_TRANSIT
                    ? "Neighborhood exploration"
                    : "Flexible cultural time",
                "Uncommitted time retained for pace and local adjustments.",
                freeStart,
                freeStart.plus(Duration.ofHours(2)),
                destination(requirement),
                strategy == PlanStrategy.LOW_TRANSIT ? "Dongcheng" : "Xicheng",
                ZERO,
                requirement.currency(),
                List.of()));

    List<DayPlan> days = new ArrayList<>();
    int dayNumber = 1;
    for (Map.Entry<LocalDate, List<ItineraryItem>> entry : itemsByDate.entrySet()) {
      List<ItineraryItem> sorted =
          entry.getValue().stream()
              .sorted(java.util.Comparator.comparing(ItineraryItem::startTime))
              .toList();
      days.add(
          new DayPlan(
              dayNumber++,
              entry.getKey(),
              strategy == PlanStrategy.LOW_TRANSIT
                  ? "Low-transit neighborhood day"
                  : "Balanced culture and dining",
              sorted));
    }
    return List.copyOf(days);
  }

  private static ItineraryItem bookingItem(
      UUID familyId,
      String alternativeKey,
      ParsedBookingEntity booking,
      String city,
      UUID publicEvidenceId) {
    ItemType type =
        switch (booking.getType()) {
          case FLIGHT, TRAIN -> ItemType.TRANSPORT;
          case HOTEL -> ItemType.ACCOMMODATION;
          case TICKET -> ItemType.ATTRACTION;
          case OTHER -> ItemType.OTHER;
        };
    String title =
        switch (booking.getType()) {
          case HOTEL -> value(booking.getHotelName(), "Confirmed hotel");
          case FLIGHT, TRAIN ->
              value(booking.getTransportNumber(), "Confirmed " + booking.getType().name());
          case TICKET -> "Confirmed attraction ticket";
          case OTHER -> "Confirmed booking";
        };
    return item(
        familyId,
        alternativeKey,
        "booking-" + booking.getBookingId(),
        type,
        title,
        "Human-confirmed parsed booking.",
        booking.getStartTime(),
        booking.getEndTime(),
        value(booking.getDestination(), city),
        value(booking.getHotelAddress(), "Confirmed booking"),
        booking.getAmount() == null ? ZERO : booking.getAmount(),
        booking.getCurrency() == null ? "CNY" : booking.getCurrency(),
        ids(publicEvidenceId));
  }

  private static ItineraryItem item(
      UUID familyId,
      String alternativeKey,
      String key,
      ItemType type,
      String title,
      String description,
      Instant start,
      Instant end,
      String placeName,
      String area,
      BigDecimal amount,
      String currency,
      List<UUID> evidenceIds) {
    return item(
        familyId,
        alternativeKey,
        key,
        type,
        title,
        description,
        start,
        end,
        placeName,
        area,
        null,
        null,
        amount,
        currency,
        evidenceIds);
  }

  private static ItineraryItem item(
      UUID familyId,
      String alternativeKey,
      String key,
      ItemType type,
      String title,
      String description,
      Instant start,
      Instant end,
      String placeName,
      String area,
      BigDecimal latitude,
      BigDecimal longitude,
      BigDecimal amount,
      String currency,
      List<UUID> evidenceIds) {
    UUID itemId =
        UUID.nameUUIDFromBytes(
            (familyId + ":" + alternativeKey + ":" + key).getBytes(StandardCharsets.UTF_8));
    return new ItineraryItem(
        itemId,
        type,
        title,
        description,
        start,
        end,
        new Place(null, placeName, area, latitude, longitude),
        amount.setScale(4, RoundingMode.HALF_UP),
        currency,
        evidenceIds);
  }

  private static BudgetSummary budget(List<DayPlan> days, BigDecimal totalBudget, String currency) {
    List<BudgetItem> items = new ArrayList<>();
    List<DailyBudget> daily = new ArrayList<>();
    for (DayPlan day : days) {
      BigDecimal dayTotal = ZERO;
      for (ItineraryItem item : day.items()) {
        items.add(
            new BudgetItem(
                budgetCategory(item.type()), item.title(), item.amount(), item.currency()));
        if (currency.equals(item.currency())) {
          dayTotal = dayTotal.add(item.amount());
        }
      }
      daily.add(new DailyBudget(day.date(), dayTotal.setScale(4), currency));
    }
    BigDecimal estimated =
        items.stream()
            .filter(item -> currency.equals(item.currency()))
            .map(BudgetItem::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(4, RoundingMode.HALF_UP);
    return new BudgetSummary(
        totalBudget.setScale(4, RoundingMode.HALF_UP),
        estimated,
        totalBudget.subtract(estimated).setScale(4, RoundingMode.HALF_UP),
        currency,
        List.copyOf(items),
        List.copyOf(daily));
  }

  private Map<UUID, Integer> commuteRequirements(List<DayPlan> days, PlanStrategy strategy) {
    Map<UUID, Integer> requirements = new LinkedHashMap<>();
    for (DayPlan day : days) {
      for (int index = 1; index < day.items().size(); index++) {
        ItineraryItem item = day.items().get(index);
        int minutes =
            strategy == PlanStrategy.LOW_TRANSIT
                ? Math.max(15, properties.getDefaultCommuteMinutes() / 2)
                : properties.getDefaultCommuteMinutes();
        requirements.put(item.itemId(), minutes);
      }
    }
    return Map.copyOf(requirements);
  }

  private static Map<UUID, OperatingWindow> operatingWindows(List<DayPlan> days) {
    Map<UUID, OperatingWindow> windows = new LinkedHashMap<>();
    days.stream()
        .flatMap(day -> day.items().stream())
        .filter(item -> item.type() == ItemType.ATTRACTION)
        .forEach(
            item ->
                windows.put(
                    item.itemId(), new OperatingWindow(LocalTime.of(9, 0), LocalTime.of(17, 0))));
    return Map.copyOf(windows);
  }

  private static Map<String, BigDecimal> categoryLimits(BigDecimal totalBudget) {
    return Map.of(
        ItemType.TRANSPORT.name(), totalBudget.multiply(new BigDecimal("0.40")),
        ItemType.ACCOMMODATION.name(), totalBudget.multiply(new BigDecimal("0.55")),
        ItemType.ATTRACTION.name(), totalBudget.multiply(new BigDecimal("0.20")),
        ItemType.DINING.name(), totalBudget.multiply(new BigDecimal("0.25")));
  }

  private static Set<ItemType> confirmedTypes(List<ParsedBookingEntity> bookings) {
    Set<ItemType> types = EnumSet.noneOf(ItemType.class);
    bookings.stream()
        .filter(PlanCandidateAssembler::confirmed)
        .forEach(
            booking -> {
              if (booking.getType() == BookingType.FLIGHT
                  || booking.getType() == BookingType.TRAIN) {
                types.add(ItemType.TRANSPORT);
              }
              if (booking.getType() == BookingType.HOTEL) {
                types.add(ItemType.ACCOMMODATION);
              }
            });
    return Set.copyOf(types);
  }

  private static Map<UUID, String> itemAreas(List<DayPlan> days) {
    Map<UUID, String> areas = new LinkedHashMap<>();
    days.stream()
        .flatMap(day -> day.items().stream())
        .forEach(item -> areas.put(item.itemId(), value(item.location().address(), "Unknown")));
    return Map.copyOf(areas);
  }

  private static List<Evidence> toEvidence(
      List<AgentEvidence> agentEvidence, List<ParsedBookingEntity> bookings) {
    Map<String, Evidence> evidence = new LinkedHashMap<>();
    for (AgentEvidence item : agentEvidence) {
      EvidenceSourceType sourceType = sourceType(item.sourceType());
      UUID evidenceId = stableId("evidence:" + item.sourceId());
      evidence.putIfAbsent(
          item.sourceId(),
          new Evidence(
              evidenceId,
              sourceType,
              item.sourceId(),
              item.title(),
              item.excerpt(),
              (URI) null,
              Instant.now()));
    }
    for (ParsedBookingEntity booking : bookings) {
      if (!confirmed(booking)) {
        continue;
      }
      String sourceId = "booking:" + booking.getBookingId();
      evidence.put(
          sourceId,
          new Evidence(
              stableId("evidence:" + sourceId),
              EvidenceSourceType.DOCUMENT,
              sourceId,
              "Confirmed " + booking.getType().name().toLowerCase() + " booking",
              "Human-confirmed booking constraints were merged without exposing OCR text.",
              null,
              booking.getUpdatedAt()));
    }
    return List.copyOf(evidence.values());
  }

  private static List<Conflict> semanticConflicts(JsonNode plan) {
    List<Conflict> conflicts = new ArrayList<>();
    for (JsonNode issue : plan.path("review").path("issues")) {
      String code = "SEMANTIC_" + issue.path("code").asText("REVIEW_RISK");
      Severity severity =
          switch (issue.path("severity").asText("WARNING")) {
            case "ERROR" -> Severity.ERROR;
            case "INFO" -> Severity.INFO;
            default -> Severity.WARNING;
          };
      conflicts.add(
          new Conflict(
              stableId("semantic:" + code + ":" + issue.path("message").asText()),
              code,
              ConflictType.PREFERENCE,
              severity,
              issue.path("message").asText("Semantic review risk."),
              "/summary",
              List.of(),
              false,
              ConflictStatus.OPEN));
    }
    return List.copyOf(conflicts);
  }

  private static java.util.Optional<Instant> confirmedArrival(
      List<ParsedBookingEntity> bookings, String city) {
    return bookings.stream()
        .filter(PlanCandidateAssembler::confirmed)
        .filter(
            booking ->
                booking.getType() == BookingType.FLIGHT || booking.getType() == BookingType.TRAIN)
        .filter(
            booking -> booking.getDestination() == null || booking.getDestination().contains(city))
        .map(ParsedBookingEntity::getEndTime)
        .filter(java.util.Objects::nonNull)
        .findFirst();
  }

  private static Instant transportArrival(
      JsonNode plan, TravelRequirement requirement, ZoneId zoneId) {
    return parseInstant(
        plan.path("transport").path("options").path(0).path("arrivalTime").asText(),
        requirement.startDate().atTime(10, 0).atZone(zoneId).toInstant());
  }

  private static JsonNode firstOfType(JsonNode recommendations, String type) {
    for (JsonNode recommendation : recommendations) {
      if (type.equals(recommendation.path("type").asText())) {
        return recommendation;
      }
    }
    return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
  }

  private static BigDecimal decimal(JsonNode node, String field, String fallback) {
    JsonNode value = node.path(field);
    return value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText(fallback));
  }

  private static BigDecimal decimalOrNull(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isNumber() ? value.decimalValue() : null;
  }

  private static Instant parseInstant(String value, Instant fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return OffsetDateTime.parse(value).toInstant();
  }

  private static String destination(TravelRequirement requirement) {
    return requirement.destinations().getFirst().name();
  }

  private static String budgetCategory(ItemType type) {
    return type == ItemType.FREE_TIME ? ItemType.OTHER.name() : type.name();
  }

  private static boolean confirmed(ParsedBookingEntity booking) {
    return booking.getReviewStatus() != BookingReviewStatus.NEEDS_REVIEW;
  }

  private static UUID stableId(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static List<UUID> ids(UUID id) {
    return id == null ? List.of() : List.of(id);
  }

  private static EvidenceSourceType sourceType(String sourceType) {
    try {
      return EvidenceSourceType.valueOf(sourceType);
    } catch (IllegalArgumentException exception) {
      return EvidenceSourceType.PROVIDER;
    }
  }

  private static String value(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String title(PlanStrategy strategy, String city) {
    return switch (strategy) {
      case LOW_TRANSIT -> city + " low-transit plan";
      case BUDGET -> city + " budget plan";
      case COMFORT -> city + " comfort plan";
      case CUSTOM -> city + " custom plan";
      case BALANCED -> city + " balanced plan";
    };
  }

  private static String summary(PlanStrategy strategy, String city) {
    return "A "
        + strategy.name().toLowerCase().replace('_', '-')
        + " "
        + city
        + " itinerary generated from Agent candidates and deterministic rules.";
  }
}
