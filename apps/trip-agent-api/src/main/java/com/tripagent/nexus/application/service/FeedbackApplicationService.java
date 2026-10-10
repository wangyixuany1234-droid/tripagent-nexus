package com.tripagent.nexus.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.controller.dto.ApiModels.CreateFeedbackRequest;
import com.tripagent.nexus.domain.model.Feedback;
import com.tripagent.nexus.guardrail.PromptInjectionGuard;
import com.tripagent.nexus.infrastructure.persistence.entity.FeedbackEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelPlanEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.FeedbackRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelPlanRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackApplicationService {

  private final FeedbackRepository feedbackRepository;
  private final TravelPlanRepository planRepository;
  private final TripApplicationService tripService;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final PromptInjectionGuard promptInjectionGuard;
  private final UserPreferenceService preferenceService;

  public FeedbackApplicationService(
      FeedbackRepository feedbackRepository,
      TravelPlanRepository planRepository,
      TripApplicationService tripService,
      ObjectMapper objectMapper,
      Clock clock,
      PromptInjectionGuard promptInjectionGuard,
      UserPreferenceService preferenceService) {
    this.feedbackRepository = feedbackRepository;
    this.planRepository = planRepository;
    this.tripService = tripService;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.promptInjectionGuard = promptInjectionGuard;
    this.preferenceService = preferenceService;
  }

  @Transactional
  public Feedback create(CreateFeedbackRequest request) {
    var trip = tripService.requireTrip(request.tripId());
    promptInjectionGuard.requireSafe(request.comment());
    request.tags().forEach(promptInjectionGuard::requireSafe);
    if (request.planId() != null) {
      TravelPlanEntity plan =
          planRepository
              .findById(request.planId())
              .orElseThrow(
                  () ->
                      new ApiException(
                          "PLAN_NOT_FOUND", HttpStatus.NOT_FOUND, "Travel plan was not found."));
      if (!plan.getTripId().equals(request.tripId())) {
        throw new ApiException(
            "PLAN_TRIP_MISMATCH",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "The plan does not belong to the supplied trip.");
      }
    }
    validateTarget(request);

    Instant now = clock.instant();
    boolean memoryAccepted = isMemoryEligible(request);
    FeedbackEntity entity =
        feedbackRepository.save(
            new FeedbackEntity(
                UUID.randomUUID(),
                request.tripId(),
                request.planId(),
                request.targetType(),
                request.targetId(),
                request.sentiment(),
                request.rating(),
                request.comment(),
                objectMapper.valueToTree(request.tags()),
                memoryAccepted,
                now));
    if (memoryAccepted) {
      preferenceService.createInferred(
          trip.getUserId(),
          "feedback." + request.targetType().toLowerCase(),
          request.sentiment().toLowerCase() + ": " + request.tags().get(0));
    }
    return toDomain(entity);
  }

  @Transactional(readOnly = true)
  public List<Feedback> list(UUID tripId) {
    tripService.requireTrip(tripId);
    return feedbackRepository.findByTripIdOrderByCreatedAtDesc(tripId).stream()
        .map(this::toDomain)
        .toList();
  }

  private static void validateTarget(CreateFeedbackRequest request) {
    if ("PLAN".equals(request.targetType()) && request.planId() == null) {
      throw new ApiException(
          "FEEDBACK_PLAN_REQUIRED",
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Plan feedback requires a plan.");
    }
    if (request.targetType() != null
        && !"PLAN".equals(request.targetType())
        && (request.targetId() == null || request.targetId().isBlank())) {
      throw new ApiException(
          "FEEDBACK_TARGET_REQUIRED",
          HttpStatus.UNPROCESSABLE_ENTITY,
          "The selected feedback target is required.");
    }
  }

  private static boolean isMemoryEligible(CreateFeedbackRequest request) {
    return request.targetType() != null
        && request.sentiment() != null
        && !request.tags().isEmpty()
        && ("PLAN".equals(request.targetType()) || "PLACE".equals(request.targetType()));
  }

  private Feedback toDomain(FeedbackEntity entity) {
    return new Feedback(
        entity.getFeedbackId(),
        entity.getTripId(),
        entity.getPlanId(),
        entity.getTargetType(),
        entity.getTargetId(),
        entity.getSentiment(),
        entity.getRating(),
        entity.getComment(),
        objectMapper.convertValue(entity.getTags(), new TypeReference<List<String>>() {}),
        entity.isMemoryAccepted(),
        entity.getCreatedAt());
  }
}
