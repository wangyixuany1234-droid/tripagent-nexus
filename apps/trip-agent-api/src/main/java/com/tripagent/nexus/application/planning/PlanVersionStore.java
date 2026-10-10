package com.tripagent.nexus.application.planning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.application.planning.PlanningModels.PlanCandidate;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.domain.model.Conflict;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStatus;
import com.tripagent.nexus.domain.model.Place;
import com.tripagent.nexus.domain.model.ReplanInputSnapshot;
import com.tripagent.nexus.domain.model.TravelPlan;
import com.tripagent.nexus.domain.model.TravelRequirement;
import com.tripagent.nexus.domain.model.Traveler;
import com.tripagent.nexus.infrastructure.persistence.entity.ParsedBookingEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelPlanEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelRequirementEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TripEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.ParsedBookingRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelPlanRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelRequirementRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TripRepository;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanVersionStore {

  private final TravelPlanRepository planRepository;
  private final TravelRequirementRepository requirementRepository;
  private final ParsedBookingRepository bookingRepository;
  private final TripRepository tripRepository;
  private final TravelPlanMapper planMapper;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public PlanVersionStore(
      TravelPlanRepository planRepository,
      TravelRequirementRepository requirementRepository,
      ParsedBookingRepository bookingRepository,
      TripRepository tripRepository,
      TravelPlanMapper planMapper,
      ObjectMapper objectMapper,
      Clock clock) {
    this.planRepository = planRepository;
    this.requirementRepository = requirementRepository;
    this.bookingRepository = bookingRepository;
    this.tripRepository = tripRepository;
    this.planMapper = planMapper;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PlanningData load(UUID planId) {
    TravelPlanEntity plan = requirePlan(planId);
    TripEntity trip =
        tripRepository
            .findById(plan.getTripId())
            .orElseThrow(
                () ->
                    new ApiException(
                        "TRIP_NOT_FOUND", HttpStatus.NOT_FOUND, "Trip was not found."));
    TravelRequirementEntity requirement =
        requirementRepository
            .findById(plan.getTripId())
            .orElseThrow(
                () ->
                    new ApiException(
                        "TRAVEL_REQUIREMENT_NOT_FOUND",
                        HttpStatus.CONFLICT,
                        "A travel requirement is required before planning."));
    return new PlanningData(
        planMapper.toDomain(plan),
        plan.getPreviousPlanId() == null
            ? null
            : planRepository
                .findById(plan.getPreviousPlanId())
                .map(planMapper::toDomain)
                .orElse(null),
        toDomain(requirement),
        trip.getUserId(),
        ZoneId.of(trip.getTimeZone()),
        bookingRepository.findByTripId(plan.getTripId()));
  }

  @Transactional
  public TravelPlan complete(
      UUID planId,
      PlanCandidate candidate,
      List<Conflict> conflicts,
      PlanStatus status,
      String changeSummary) {
    TravelPlanEntity plan = requirePlan(planId);
    plan.complete(
        candidate.summary(),
        objectMapper.valueToTree(candidate.days()),
        objectMapper.valueToTree(candidate.budgetSummary()),
        objectMapper.valueToTree(conflicts),
        objectMapper.valueToTree(candidate.evidence()),
        status,
        changeSummary,
        clock.instant());
    return planMapper.toDomain(planRepository.save(plan));
  }

  @Transactional
  public TravelPlan createCompletedAlternative(
      UUID tripId,
      UUID planFamilyId,
      PlanCandidate candidate,
      List<Conflict> conflicts,
      PlanStatus status,
      String triggerReason) {
    TravelPlanEntity plan =
        new TravelPlanEntity(
            UUID.randomUUID(),
            tripId,
            planFamilyId,
            null,
            candidate.alternativeKey(),
            candidate.strategy(),
            1,
            candidate.title(),
            candidate.summary(),
            objectMapper.valueToTree(candidate.days()),
            objectMapper.valueToTree(candidate.budgetSummary()),
            objectMapper.valueToTree(conflicts),
            objectMapper.valueToTree(candidate.evidence()),
            triggerReason,
            objectMapper.valueToTree(ReplanInputSnapshot.empty()),
            "Initial alternative generated.",
            false,
            clock.instant());
    plan.complete(
        candidate.summary(),
        objectMapper.valueToTree(candidate.days()),
        objectMapper.valueToTree(candidate.budgetSummary()),
        objectMapper.valueToTree(conflicts),
        objectMapper.valueToTree(candidate.evidence()),
        status,
        "Initial alternative generated.",
        clock.instant());
    return planMapper.toDomain(planRepository.save(plan));
  }

  @Transactional
  public TravelPlan createCompletedSuccessor(
      TravelPlan previous,
      PlanCandidate candidate,
      List<Conflict> conflicts,
      PlanStatus status,
      String triggerReason,
      ReplanInputSnapshot replanInput,
      String changeSummary,
      boolean autoReplanned) {
    int version =
        planRepository
                .findMaxVersionByPlanFamilyId(previous.planFamilyId())
                .orElse(previous.version())
            + 1;
    TravelPlanEntity plan =
        new TravelPlanEntity(
            UUID.randomUUID(),
            previous.tripId(),
            previous.planFamilyId(),
            previous.planId(),
            previous.alternativeKey(),
            previous.strategy(),
            version,
            previous.title(),
            candidate.summary(),
            objectMapper.valueToTree(candidate.days()),
            objectMapper.valueToTree(candidate.budgetSummary()),
            objectMapper.valueToTree(conflicts),
            objectMapper.valueToTree(candidate.evidence()),
            triggerReason,
            objectMapper.valueToTree(replanInput),
            changeSummary,
            autoReplanned,
            clock.instant());
    plan.complete(
        candidate.summary(),
        objectMapper.valueToTree(candidate.days()),
        objectMapper.valueToTree(candidate.budgetSummary()),
        objectMapper.valueToTree(conflicts),
        objectMapper.valueToTree(candidate.evidence()),
        status,
        changeSummary,
        clock.instant());
    return planMapper.toDomain(planRepository.save(plan));
  }

  @Transactional
  public void fail(UUID planId, String failureCode) {
    TravelPlanEntity plan = requirePlan(planId);
    plan.markFailed("Planning failed with " + failureCode + ".", clock.instant());
    planRepository.save(plan);
  }

  private TravelPlanEntity requirePlan(UUID planId) {
    return planRepository
        .findById(planId)
        .orElseThrow(
            () ->
                new ApiException(
                    "PLAN_NOT_FOUND", HttpStatus.NOT_FOUND, "Travel plan was not found."));
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

  public record PlanningData(
      TravelPlan plan,
      TravelPlan previousPlan,
      TravelRequirement requirement,
      UUID userId,
      ZoneId zoneId,
      List<ParsedBookingEntity> bookings) {

    public PlanningData {
      bookings = List.copyOf(bookings);
    }
  }
}
