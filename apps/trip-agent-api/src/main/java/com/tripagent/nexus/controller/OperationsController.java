package com.tripagent.nexus.controller;

import com.tripagent.nexus.application.idempotency.IdempotencyService;
import com.tripagent.nexus.application.service.DeadLetterService;
import com.tripagent.nexus.application.service.OperationsAuthorizationService;
import com.tripagent.nexus.controller.dto.ApiModels.DeadLetterList;
import com.tripagent.nexus.controller.dto.ApiModels.ReplayDeadLetterRequest;
import com.tripagent.nexus.domain.model.DeadLetterRecord;
import com.tripagent.nexus.domain.model.DomainEnums.DeadLetterStatus;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/dead-letters")
public class OperationsController {

  private final DeadLetterService deadLetterService;
  private final OperationsAuthorizationService authorizationService;
  private final IdempotencyService idempotencyService;

  public OperationsController(
      DeadLetterService deadLetterService,
      OperationsAuthorizationService authorizationService,
      IdempotencyService idempotencyService) {
    this.deadLetterService = deadLetterService;
    this.authorizationService = authorizationService;
    this.idempotencyService = idempotencyService;
  }

  @GetMapping
  public DeadLetterList list(
      @RequestHeader(value = "X-Operations-Token", required = false) String operationsToken,
      @RequestParam(required = false) DeadLetterStatus status) {
    authorizationService.authorize(operationsToken);
    return new DeadLetterList(deadLetterService.list(status));
  }

  @PostMapping("/{deadLetterId}/replay")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public DeadLetterRecord replay(
      @PathVariable UUID deadLetterId,
      @RequestHeader(value = "X-Operations-Token", required = false) String operationsToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody ReplayDeadLetterRequest request) {
    authorizationService.authorize(operationsToken);
    authorizationService.enforceReplayRateLimit();
    return idempotencyService.execute(
        idempotencyKey,
        "POST:/api/v1/operations/dead-letters/{deadLetterId}/replay:" + deadLetterId,
        new ReplayFingerprint(deadLetterId, request.reason()),
        DeadLetterRecord.class,
        () -> deadLetterService.replay(deadLetterId, request.reason()));
  }

  private record ReplayFingerprint(UUID deadLetterId, String reason) {}
}
