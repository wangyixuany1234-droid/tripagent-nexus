package com.tripagent.nexus.controller;

import com.tripagent.nexus.application.idempotency.IdempotencyService;
import com.tripagent.nexus.application.security.ResourceAuthorizationService;
import com.tripagent.nexus.application.security.UserRequestRateLimitService;
import com.tripagent.nexus.application.service.PlanApplicationService;
import com.tripagent.nexus.controller.dto.ApiModels.ConfirmPlanRequest;
import com.tripagent.nexus.controller.dto.ApiModels.CreateTravelPlanRequest;
import com.tripagent.nexus.controller.dto.ApiModels.ReplanRequest;
import com.tripagent.nexus.controller.dto.ApiModels.TaskAccepted;
import com.tripagent.nexus.controller.dto.ApiModels.TravelPlanList;
import com.tripagent.nexus.domain.model.PlanComparison;
import com.tripagent.nexus.domain.model.TravelPlan;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PlanController {

  private final PlanApplicationService planService;
  private final IdempotencyService idempotencyService;
  private final ResourceAuthorizationService authorization;
  private final UserRequestRateLimitService rateLimits;

  public PlanController(
      PlanApplicationService planService,
      IdempotencyService idempotencyService,
      ResourceAuthorizationService authorization,
      UserRequestRateLimitService rateLimits) {
    this.planService = planService;
    this.idempotencyService = idempotencyService;
    this.authorization = authorization;
    this.rateLimits = rateLimits;
  }

  @PostMapping("/plans")
  public ResponseEntity<TaskAccepted> create(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      Authentication authentication,
      @Valid @RequestBody CreateTravelPlanRequest request) {
    authorization.requireTrip(authentication, request.tripId());
    rateLimits.enforcePlanSubmissionLimit(authentication);
    TaskAccepted accepted =
        idempotencyService.execute(
            idempotencyKey,
            "POST:/api/v1/plans",
            request,
            TaskAccepted.class,
            () -> planService.create(request));
    return accepted(accepted);
  }

  @GetMapping("/plans/{planId}")
  public TravelPlan get(@PathVariable UUID planId, Authentication authentication) {
    authorization.requirePlan(authentication, planId);
    return planService.get(planId);
  }

  @GetMapping("/trips/{tripId}/plans")
  public TravelPlanList list(@PathVariable UUID tripId, Authentication authentication) {
    authorization.requireTrip(authentication, tripId);
    return new TravelPlanList(planService.list(tripId));
  }

  @PostMapping("/plans/{planId}/replan")
  public ResponseEntity<TaskAccepted> replan(
      @PathVariable UUID planId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      Authentication authentication,
      @Valid @RequestBody ReplanRequest request) {
    authorization.requirePlan(authentication, planId);
    TaskAccepted accepted =
        idempotencyService.execute(
            idempotencyKey,
            "POST:/api/v1/plans/{planId}/replan:" + planId,
            request,
            TaskAccepted.class,
            () -> planService.replan(planId, request));
    return accepted(accepted);
  }

  @GetMapping("/plans/{planId}/compare/{otherPlanId}")
  public PlanComparison compare(
      @PathVariable UUID planId, @PathVariable UUID otherPlanId, Authentication authentication) {
    authorization.requirePlan(authentication, planId);
    authorization.requirePlan(authentication, otherPlanId);
    return planService.compare(planId, otherPlanId);
  }

  @PostMapping("/plans/{planId}/confirm")
  public TravelPlan confirm(
      @PathVariable UUID planId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      Authentication authentication,
      @Valid @RequestBody ConfirmPlanRequest request) {
    authorization.requirePlan(authentication, planId);
    return idempotencyService.execute(
        idempotencyKey,
        "POST:/api/v1/plans/{planId}/confirm:" + planId,
        request,
        TravelPlan.class,
        () -> planService.confirm(planId, request));
  }

  private static ResponseEntity<TaskAccepted> accepted(TaskAccepted accepted) {
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/plans/" + accepted.resourceId()))
        .body(accepted);
  }
}
