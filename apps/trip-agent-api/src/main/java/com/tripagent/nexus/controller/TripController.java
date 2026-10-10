package com.tripagent.nexus.controller;

import com.tripagent.nexus.application.idempotency.IdempotencyService;
import com.tripagent.nexus.application.security.ResourceAuthorizationService;
import com.tripagent.nexus.application.service.TripApplicationService;
import com.tripagent.nexus.controller.dto.ApiModels.CreateTripRequest;
import com.tripagent.nexus.controller.dto.ApiModels.SaveTravelRequirementRequest;
import com.tripagent.nexus.domain.model.TravelRequirement;
import com.tripagent.nexus.domain.model.Trip;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

  private final TripApplicationService tripService;
  private final IdempotencyService idempotencyService;
  private final ResourceAuthorizationService authorization;

  public TripController(
      TripApplicationService tripService,
      IdempotencyService idempotencyService,
      ResourceAuthorizationService authorization) {
    this.tripService = tripService;
    this.idempotencyService = idempotencyService;
    this.authorization = authorization;
  }

  @PostMapping
  public ResponseEntity<Trip> create(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      Authentication authentication,
      @Valid @RequestBody CreateTripRequest request) {
    authorization.requireSameUser(authentication, request.userId());
    Trip trip =
        idempotencyService.execute(
            idempotencyKey,
            "POST:/api/v1/trips",
            request,
            Trip.class,
            () -> tripService.create(request));
    return ResponseEntity.created(URI.create("/api/v1/trips/" + trip.tripId())).body(trip);
  }

  @GetMapping("/{tripId}")
  public Trip get(@PathVariable UUID tripId, Authentication authentication) {
    authorization.requireTrip(authentication, tripId);
    return tripService.get(tripId, null);
  }

  @PutMapping("/{tripId}/requirements")
  public TravelRequirement saveRequirement(
      @PathVariable UUID tripId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      Authentication authentication,
      @Valid @RequestBody SaveTravelRequirementRequest request) {
    authorization.requireTrip(authentication, tripId);
    return idempotencyService.execute(
        idempotencyKey,
        "PUT:/api/v1/trips/{tripId}/requirements:" + tripId,
        request,
        TravelRequirement.class,
        () -> tripService.saveRequirement(tripId, request));
  }
}
