package com.tripagent.nexus.controller;

import com.tripagent.nexus.application.idempotency.IdempotencyService;
import com.tripagent.nexus.application.security.ResourceAuthorizationService;
import com.tripagent.nexus.application.service.FeedbackApplicationService;
import com.tripagent.nexus.controller.dto.ApiModels.CreateFeedbackRequest;
import com.tripagent.nexus.controller.dto.ApiModels.FeedbackList;
import com.tripagent.nexus.domain.model.Feedback;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

  private final FeedbackApplicationService feedbackService;
  private final IdempotencyService idempotencyService;
  private final ResourceAuthorizationService authorization;

  public FeedbackController(
      FeedbackApplicationService feedbackService,
      IdempotencyService idempotencyService,
      ResourceAuthorizationService authorization) {
    this.feedbackService = feedbackService;
    this.idempotencyService = idempotencyService;
    this.authorization = authorization;
  }

  @GetMapping
  public FeedbackList list(@RequestParam UUID tripId, Authentication authentication) {
    authorization.requireTrip(authentication, tripId);
    return new FeedbackList(feedbackService.list(tripId));
  }

  @PostMapping
  public ResponseEntity<Feedback> create(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      Authentication authentication,
      @Valid @RequestBody CreateFeedbackRequest request) {
    authorization.requireTrip(authentication, request.tripId());
    Feedback feedback =
        idempotencyService.execute(
            idempotencyKey,
            "POST:/api/v1/feedback",
            request,
            Feedback.class,
            () -> feedbackService.create(request));
    return ResponseEntity.status(201).body(feedback);
  }
}
