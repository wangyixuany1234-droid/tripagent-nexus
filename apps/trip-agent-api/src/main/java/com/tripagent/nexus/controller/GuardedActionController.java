package com.tripagent.nexus.controller;

import com.tripagent.nexus.application.idempotency.IdempotencyService;
import com.tripagent.nexus.application.security.AuthenticatedActorService;
import com.tripagent.nexus.controller.dto.ApiModels.ConfirmGuardedActionRequest;
import com.tripagent.nexus.controller.dto.ApiModels.CreateGuardedActionRequest;
import com.tripagent.nexus.controller.dto.ApiModels.RejectGuardedActionRequest;
import com.tripagent.nexus.domain.model.GuardedAction;
import com.tripagent.nexus.guardrail.GuardedActionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/actions")
public class GuardedActionController {

  private static final String ACTOR_PATTERN = "^[A-Za-z0-9._:@-]{1,128}$";

  private final GuardedActionService actionService;
  private final IdempotencyService idempotencyService;
  private final AuthenticatedActorService actors;

  public GuardedActionController(
      GuardedActionService actionService,
      IdempotencyService idempotencyService,
      AuthenticatedActorService actors) {
    this.actionService = actionService;
    this.idempotencyService = idempotencyService;
    this.actors = actors;
  }

  @PostMapping
  public ResponseEntity<GuardedAction> propose(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader(value = "X-Actor-Id", required = false) @Pattern(regexp = ACTOR_PATTERN) String testActorId,
      Authentication authentication,
      @Valid @RequestBody CreateGuardedActionRequest request) {
    String actorId = actors.actorId(authentication, testActorId);
    GuardedAction action =
        idempotencyService.execute(
            idempotencyKey,
            "POST:/api/v1/actions:" + actorId,
            request,
            GuardedAction.class,
            () -> actionService.propose(request, actorId));
    return ResponseEntity.created(URI.create("/api/v1/actions/" + action.actionId())).body(action);
  }

  @GetMapping("/{actionId}")
  public GuardedAction get(
      @PathVariable UUID actionId,
      @RequestHeader(value = "X-Actor-Id", required = false) @Pattern(regexp = ACTOR_PATTERN) String testActorId,
      Authentication authentication) {
    String actorId = actors.actorId(authentication, testActorId);
    return actionService.get(actionId, actorId);
  }

  @PostMapping("/{actionId}/confirm")
  public GuardedAction confirm(
      @PathVariable UUID actionId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader(value = "X-Actor-Id", required = false) @Pattern(regexp = ACTOR_PATTERN) String testActorId,
      Authentication authentication,
      @Valid @RequestBody ConfirmGuardedActionRequest request) {
    String actorId = actors.actorId(authentication, testActorId);
    return idempotencyService.execute(
        idempotencyKey,
        "POST:/api/v1/actions/{actionId}/confirm:" + actionId + ":" + actorId,
        request,
        GuardedAction.class,
        () -> actionService.confirm(actionId, request, actorId));
  }

  @PostMapping("/{actionId}/reject")
  public GuardedAction reject(
      @PathVariable UUID actionId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader(value = "X-Actor-Id", required = false) @Pattern(regexp = ACTOR_PATTERN) String testActorId,
      Authentication authentication,
      @Valid @RequestBody RejectGuardedActionRequest request) {
    String actorId = actors.actorId(authentication, testActorId);
    return idempotencyService.execute(
        idempotencyKey,
        "POST:/api/v1/actions/{actionId}/reject:" + actionId + ":" + actorId,
        request,
        GuardedAction.class,
        () -> actionService.reject(actionId, request, actorId));
  }
}
