package com.tripagent.nexus.controller;

import com.tripagent.nexus.application.idempotency.IdempotencyService;
import com.tripagent.nexus.application.service.OperationsAuthorizationService;
import com.tripagent.nexus.controller.dto.ApiModels.CreateEvaluationRunRequest;
import com.tripagent.nexus.controller.dto.ApiModels.TaskAccepted;
import com.tripagent.nexus.evaluation.EvaluationApplicationService;
import com.tripagent.nexus.evaluation.EvaluationModels.EvaluationRun;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/evaluations/runs")
public class EvaluationController {

  private final EvaluationApplicationService evaluationService;
  private final OperationsAuthorizationService authorizationService;
  private final IdempotencyService idempotencyService;

  public EvaluationController(
      EvaluationApplicationService evaluationService,
      OperationsAuthorizationService authorizationService,
      IdempotencyService idempotencyService) {
    this.evaluationService = evaluationService;
    this.authorizationService = authorizationService;
    this.idempotencyService = idempotencyService;
  }

  @PostMapping
  public ResponseEntity<TaskAccepted> start(
      @RequestHeader(value = "X-Operations-Token", required = false) String operationsToken,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CreateEvaluationRunRequest request) {
    authorizationService.authorize(operationsToken);
    TaskAccepted accepted =
        idempotencyService.execute(
            idempotencyKey,
            "POST:/api/v1/evaluations/runs",
            request,
            TaskAccepted.class,
            () -> evaluationService.start(request));
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/evaluations/runs/" + accepted.resourceId()))
        .body(accepted);
  }

  @GetMapping("/{runId}")
  public EvaluationRun get(
      @PathVariable UUID runId,
      @RequestHeader(value = "X-Operations-Token", required = false) String operationsToken) {
    authorizationService.authorize(operationsToken);
    return evaluationService.get(runId);
  }
}
