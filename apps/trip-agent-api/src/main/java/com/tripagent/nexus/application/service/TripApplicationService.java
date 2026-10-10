package com.tripagent.nexus.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.controller.dto.ApiModels.CreateTripRequest;
import com.tripagent.nexus.controller.dto.ApiModels.PlaceInput;
import com.tripagent.nexus.controller.dto.ApiModels.SaveTravelRequirementRequest;
import com.tripagent.nexus.controller.dto.ApiModels.TravelerInput;
import com.tripagent.nexus.domain.model.Place;
import com.tripagent.nexus.domain.model.TravelRequirement;
import com.tripagent.nexus.domain.model.Traveler;
import com.tripagent.nexus.domain.model.Trip;
import com.tripagent.nexus.guardrail.PromptInjectionGuard;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelRequirementEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TripEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelRequirementRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TripRepository;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripApplicationService {

  private static final Set<String> SUPPORTED_CURRENCIES =
      Set.of("AUD", "CAD", "CNY", "EUR", "GBP", "HKD", "JPY", "KRW", "SGD", "USD");

  private final TripRepository tripRepository;
  private final TravelRequirementRepository requirementRepository;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final PromptInjectionGuard promptInjectionGuard;

  public TripApplicationService(
      TripRepository tripRepository,
      TravelRequirementRepository requirementRepository,
      ObjectMapper objectMapper,
      Clock clock,
      PromptInjectionGuard promptInjectionGuard) {
    this.tripRepository = tripRepository;
    this.requirementRepository = requirementRepository;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.promptInjectionGuard = promptInjectionGuard;
  }

  @Transactional
  public Trip create(CreateTripRequest request) {
    promptInjectionGuard.requireSafe(request.title());
    validateTimeZone(request.timeZone());
    Instant now = clock.instant();
    TripEntity entity =
        tripRepository.save(
            new TripEntity(
                UUID.randomUUID(), request.userId(), request.title(), request.timeZone(), now));
    return toDomain(entity, null);
  }

  @Transactional(readOnly = true)
  public Trip get(UUID tripId, UUID requestingUserId) {
    TripEntity trip = requireTrip(tripId);
    if (requestingUserId != null && !trip.getUserId().equals(requestingUserId)) {
      throw new ApiException("TRIP_NOT_FOUND", HttpStatus.NOT_FOUND, "Trip was not found.");
    }
    TravelRequirement requirement =
        requirementRepository.findById(tripId).map(this::toDomain).orElse(null);
    return toDomain(trip, requirement);
  }

  @Transactional
  public TravelRequirement saveRequirement(UUID tripId, SaveTravelRequirementRequest request) {
    validatePromptInputs(request);
    if (request.totalBudget().signum() <= 0) {
      throw new ApiException(
          "TRIP_BUDGET_INVALID",
          HttpStatus.UNPROCESSABLE_ENTITY,
          "totalBudget must be greater than zero.");
    }
    if (!SUPPORTED_CURRENCIES.contains(request.currency())) {
      throw new ApiException(
          "TRIP_CURRENCY_UNSUPPORTED",
          HttpStatus.UNPROCESSABLE_ENTITY,
          "The selected currency is not supported.");
    }
    if (request.endDate().isBefore(request.startDate())) {
      throw new ApiException(
          "TRIP_DATE_RANGE_INVALID",
          HttpStatus.UNPROCESSABLE_ENTITY,
          "endDate must not be before startDate.");
    }
    TripEntity trip = requireTrip(tripId);
    Instant now = clock.instant();
    List<Place> destinations =
        request.destinations().stream().map(TripApplicationService::toDomain).toList();
    List<Traveler> travelers =
        request.travelers().stream().map(TripApplicationService::toDomain).toList();

    TravelRequirementEntity entity =
        requirementRepository
            .findById(tripId)
            .orElseGet(
                () ->
                    new TravelRequirementEntity(
                        tripId,
                        objectMapper.valueToTree(toDomain(request.origin())),
                        objectMapper.valueToTree(destinations),
                        request.startDate(),
                        request.endDate(),
                        objectMapper.valueToTree(travelers),
                        request.totalBudget(),
                        request.currency(),
                        objectMapper.valueToTree(request.interests()),
                        request.pace(),
                        objectMapper.valueToTree(request.mustVisit()),
                        objectMapper.valueToTree(request.avoid()),
                        request.freeTextConstraints(),
                        now));
    entity.update(
        objectMapper.valueToTree(toDomain(request.origin())),
        objectMapper.valueToTree(destinations),
        request.startDate(),
        request.endDate(),
        objectMapper.valueToTree(travelers),
        request.totalBudget(),
        request.currency(),
        objectMapper.valueToTree(request.interests()),
        request.pace(),
        objectMapper.valueToTree(request.mustVisit()),
        objectMapper.valueToTree(request.avoid()),
        request.freeTextConstraints(),
        now);
    requirementRepository.save(entity);
    trip.markReady(now);
    tripRepository.save(trip);
    return toDomain(entity);
  }

  @Transactional(readOnly = true)
  public TripEntity requireTrip(UUID tripId) {
    return tripRepository
        .findById(tripId)
        .orElseThrow(
            () -> new ApiException("TRIP_NOT_FOUND", HttpStatus.NOT_FOUND, "Trip was not found."));
  }

  private TravelRequirement toDomain(TravelRequirementEntity entity) {
    return new TravelRequirement(
        entity.getTripId(),
        objectMapper.convertValue(entity.getOrigin(), Place.class),
        objectMapper.convertValue(entity.getDestinations(), new TypeReference<List<Place>>() {}),
        entity.getStartDate(),
        entity.getEndDate(),
        objectMapper.convertValue(entity.getTravelers(), new TypeReference<List<Traveler>>() {}),
        entity.getTotalBudget(),
        entity.getCurrency(),
        objectMapper.convertValue(entity.getInterests(), new TypeReference<List<String>>() {}),
        entity.getPace(),
        objectMapper.convertValue(entity.getMustVisit(), new TypeReference<List<String>>() {}),
        objectMapper.convertValue(entity.getAvoid(), new TypeReference<List<String>>() {}),
        entity.getFreeTextConstraints(),
        entity.getUpdatedAt());
  }

  private static Trip toDomain(TripEntity entity, TravelRequirement requirement) {
    return new Trip(
        entity.getTripId(),
        entity.getUserId(),
        entity.getTitle(),
        entity.getTimeZone(),
        entity.getStatus(),
        requirement,
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private static Place toDomain(PlaceInput input) {
    return new Place(
        input.placeId(), input.name(), input.address(), input.latitude(), input.longitude());
  }

  private static Traveler toDomain(TravelerInput input) {
    return new Traveler(
        input.travelerId(),
        input.displayName(),
        input.type(),
        List.copyOf(input.accessibilityNeeds()));
  }

  private static void validateTimeZone(String timeZone) {
    try {
      ZoneId.of(timeZone);
    } catch (DateTimeException exception) {
      throw new ApiException(
          "TIME_ZONE_INVALID",
          HttpStatus.UNPROCESSABLE_ENTITY,
          "timeZone must be a valid IANA time zone.");
    }
  }

  private void validatePromptInputs(SaveTravelRequirementRequest request) {
    requireNoNullElements(request.destinations(), "destinations");
    requireNoNullElements(request.travelers(), "travelers");
    requireNoNullElements(request.interests(), "interests");
    requireNoNullElements(request.mustVisit(), "mustVisit");
    requireNoNullElements(request.avoid(), "avoid");
    request
        .travelers()
        .forEach(
            traveler -> requireNoNullElements(traveler.accessibilityNeeds(), "accessibilityNeeds"));
    promptInjectionGuard.requireSafe(request.origin().name());
    request.destinations().forEach(place -> promptInjectionGuard.requireSafe(place.name()));
    request.interests().forEach(promptInjectionGuard::requireSafe);
    request.mustVisit().forEach(promptInjectionGuard::requireSafe);
    request.avoid().forEach(promptInjectionGuard::requireSafe);
    promptInjectionGuard.requireSafe(request.freeTextConstraints());
  }

  private static void requireNoNullElements(List<?> values, String field) {
    if (values.stream().anyMatch(value -> value == null)) {
      throw new ApiException(
          "VALIDATION_FAILED",
          HttpStatus.UNPROCESSABLE_ENTITY,
          field + " must not contain null values.");
    }
  }
}
