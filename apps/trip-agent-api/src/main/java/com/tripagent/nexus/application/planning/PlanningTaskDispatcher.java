package com.tripagent.nexus.application.planning;

import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.planning.enabled", havingValue = "true", matchIfMissing = true)
public class PlanningTaskDispatcher {

  private final PlanningWorkflowService workflowService;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public PlanningTaskDispatcher(PlanningWorkflowService workflowService) {
    this.workflowService = workflowService;
  }

  public void dispatch(UUID taskId, UUID planId, TaskType taskType) {
    executor.submit(() -> workflowService.execute(taskId, planId, taskType));
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }
}
