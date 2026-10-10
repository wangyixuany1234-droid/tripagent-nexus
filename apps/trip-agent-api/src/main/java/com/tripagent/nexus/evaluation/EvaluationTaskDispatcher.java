package com.tripagent.nexus.evaluation;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class EvaluationTaskDispatcher {

  private final ObjectProvider<EvaluationApplicationService> evaluationService;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public EvaluationTaskDispatcher(ObjectProvider<EvaluationApplicationService> evaluationService) {
    this.evaluationService = evaluationService;
  }

  public void dispatch(UUID runId) {
    executor.submit(() -> evaluationService.getObject().execute(runId));
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }
}
