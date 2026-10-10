package com.tripagent.nexus.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.application.service.TaskApplicationService;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.controller.dto.ApiModels.CreateEvaluationRunRequest;
import com.tripagent.nexus.controller.dto.ApiModels.TaskAccepted;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.evaluation.EvaluationHarness.HarnessResult;
import com.tripagent.nexus.evaluation.EvaluationModels.EvaluationCaseResult;
import com.tripagent.nexus.evaluation.EvaluationModels.EvaluationDataset;
import com.tripagent.nexus.evaluation.EvaluationModels.EvaluationEnvironment;
import com.tripagent.nexus.evaluation.EvaluationModels.EvaluationMetrics;
import com.tripagent.nexus.evaluation.EvaluationModels.EvaluationRun;
import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.EvaluationCaseResultEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.EvaluationRunEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.EvaluationCaseResultRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.EvaluationRunRepository;
import com.tripagent.nexus.observability.TraceContextProvider;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class EvaluationApplicationService {

  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

  private final EvaluationRunRepository runRepository;
  private final EvaluationCaseResultRepository caseRepository;
  private final EvaluationDatasetLoader datasetLoader;
  private final EvaluationHarness harness;
  private final TaskApplicationService taskService;
  private final EvaluationTaskDispatcher dispatcher;
  private final ModelGatewayProperties modelProperties;
  private final TraceContextProvider traceContextProvider;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public EvaluationApplicationService(
      EvaluationRunRepository runRepository,
      EvaluationCaseResultRepository caseRepository,
      EvaluationDatasetLoader datasetLoader,
      EvaluationHarness harness,
      TaskApplicationService taskService,
      EvaluationTaskDispatcher dispatcher,
      ModelGatewayProperties modelProperties,
      TraceContextProvider traceContextProvider,
      ObjectMapper objectMapper,
      Clock clock) {
    this.runRepository = runRepository;
    this.caseRepository = caseRepository;
    this.datasetLoader = datasetLoader;
    this.harness = harness;
    this.taskService = taskService;
    this.dispatcher = dispatcher;
    this.modelProperties = modelProperties;
    this.traceContextProvider = traceContextProvider;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public TaskAccepted start(CreateEvaluationRunRequest request) {
    List<String> selected = request.caseIds() == null ? List.of() : List.copyOf(request.caseIds());
    datasetLoader.load(request.datasetVersion(), selected);
    Instant now = clock.instant();
    UUID runId = UUID.randomUUID();
    String traceId = traceContextProvider.currentOrNewTraceId();
    EvaluationRunEntity run =
        runRepository.save(
            new EvaluationRunEntity(
                runId,
                request.datasetVersion(),
                objectMapper.valueToTree(selected),
                objectMapper.valueToTree(environment()),
                traceId,
                now));
    TaskAccepted task = taskService.create(TaskType.EVALUATION_REPLAY, runId);
    run.attachTask(task.taskId(), now);
    runRepository.save(run);
    dispatchAfterCommit(runId);
    return task;
  }

  public void execute(UUID runId) {
    EvaluationRunEntity run = requireRun(runId);
    UUID taskId = run.getTaskId();
    try {
      if (taskService.isCancellationRequested(taskId)) {
        cancelRun(runId);
        return;
      }
      EvaluationDataset dataset = datasetLoader.load(run.getDatasetVersion(), selectedCaseIds(run));
      run.start(dataset.cases().size(), clock.instant());
      run = runRepository.save(run);
      taskService.transition(
          taskId, TaskStatus.RUNNING, "EVALUATION_STARTED", 5, "Evaluation replay started");
      HarnessResult result =
          harness.replay(
              dataset,
              (progress, caseId) -> {
                if (taskService.isCancellationRequested(taskId)) {
                  throw new EvaluationCancelledSignal();
                }
                taskService.transition(
                    taskId,
                    TaskStatus.RUNNING,
                    "EVALUATION_CASE",
                    progress,
                    "Replayed case " + caseId);
              });
      persistCases(runId, result.cases());
      run.complete(objectMapper.valueToTree(result.metrics()), clock.instant());
      runRepository.save(run);
      taskService.setResultResources(taskId, List.of(runId));
      taskService.transition(
          taskId, TaskStatus.SUCCEEDED, "COMPLETED", 100, "Evaluation replay completed");
    } catch (EvaluationCancelledSignal ignored) {
      cancelRun(runId);
    } catch (RuntimeException exception) {
      failRun(runId);
      taskService.fail(
          taskId, "EVALUATION_REPLAY_FAILED", "Evaluation replay could not be completed.", false);
    }
  }

  @Transactional(readOnly = true)
  public EvaluationRun get(UUID runId) {
    EvaluationRunEntity run = requireRun(runId);
    List<EvaluationCaseResult> cases =
        caseRepository.findByRunIdOrderByCaseIdAsc(runId).stream().map(this::toDomain).toList();
    EvaluationMetrics metrics =
        run.getMetrics() == null
            ? null
            : objectMapper.convertValue(run.getMetrics(), EvaluationMetrics.class);
    return new EvaluationRun(
        run.getRunId(),
        run.getTaskId(),
        run.getDatasetVersion(),
        run.getStatus(),
        run.getSampleSize(),
        metrics,
        cases,
        objectMapper.convertValue(run.getEnvironment(), EvaluationEnvironment.class),
        run.getTraceId(),
        run.getStartedAt(),
        run.getCompletedAt(),
        run.getCreatedAt(),
        run.getUpdatedAt());
  }

  private void persistCases(UUID runId, List<EvaluationCaseResult> cases) {
    Instant now = clock.instant();
    caseRepository.saveAll(
        cases.stream()
            .map(
                item ->
                    new EvaluationCaseResultEntity(
                        UUID.randomUUID(),
                        runId,
                        item.caseId(),
                        item.category(),
                        item.passed(),
                        item.latencyMs(),
                        item.tokenUsage(),
                        item.failureCode(),
                        objectMapper.valueToTree(item.observedCodes()),
                        now))
            .toList());
  }

  private EvaluationCaseResult toDomain(EvaluationCaseResultEntity entity) {
    return new EvaluationCaseResult(
        entity.getCaseId(),
        entity.getCategory(),
        entity.isPassed(),
        entity.getLatencyMs(),
        entity.getTokenUsage(),
        entity.getFailureCode(),
        objectMapper.convertValue(entity.getObservedCodes(), STRING_LIST));
  }

  private EvaluationEnvironment environment() {
    boolean mock = modelProperties.getProvider() == ModelGatewayProperties.Provider.MOCK;
    return new EvaluationEnvironment(
        "0.1.0-SNAPSHOT",
        System.getProperty("java.version", "unknown"),
        System.getProperty("os.name", "unknown"),
        modelProperties.getProvider().name(),
        modelProperties.getModel(),
        mock);
  }

  private List<String> selectedCaseIds(EvaluationRunEntity run) {
    return objectMapper.convertValue(run.getSelectedCaseIds(), STRING_LIST);
  }

  private EvaluationRunEntity requireRun(UUID runId) {
    return runRepository
        .findById(runId)
        .orElseThrow(
            () ->
                new ApiException(
                    "EVALUATION_RUN_NOT_FOUND",
                    HttpStatus.NOT_FOUND,
                    "Evaluation run was not found."));
  }

  private void dispatchAfterCommit(UUID runId) {
    Runnable dispatch = () -> dispatcher.dispatch(runId);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              dispatch.run();
            }
          });
    } else {
      dispatch.run();
    }
  }

  private void cancelRun(UUID runId) {
    EvaluationRunEntity run = requireRun(runId);
    run.cancel(clock.instant());
    runRepository.save(run);
  }

  private void failRun(UUID runId) {
    EvaluationRunEntity run = requireRun(runId);
    run.fail(clock.instant());
    runRepository.save(run);
  }

  private static final class EvaluationCancelledSignal extends RuntimeException {}
}
