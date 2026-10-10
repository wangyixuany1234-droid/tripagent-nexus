package com.tripagent.nexus.application.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.application.service.EventOutboxService;
import com.tripagent.nexus.controller.dto.ApiModels.TaskAccepted;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import com.tripagent.nexus.infrastructure.config.PlanningProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelPlanEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelPlanRepository;
import com.tripagent.nexus.integration.messaging.EventEnvelope;
import com.tripagent.nexus.integration.planning.PlanningEvents.PlanRequestedPayload;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PlanningCommandService {

  private final TravelPlanRepository planRepository;
  private final EventOutboxService outboxService;
  private final AsyncGovernanceProperties governanceProperties;
  private final PlanningProperties planningProperties;
  private final ObjectProvider<PlanningTaskDispatcher> localDispatcher;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public PlanningCommandService(
      TravelPlanRepository planRepository,
      EventOutboxService outboxService,
      AsyncGovernanceProperties governanceProperties,
      PlanningProperties planningProperties,
      ObjectProvider<PlanningTaskDispatcher> localDispatcher,
      ObjectMapper objectMapper,
      Clock clock) {
    this.planRepository = planRepository;
    this.outboxService = outboxService;
    this.governanceProperties = governanceProperties;
    this.planningProperties = planningProperties;
    this.localDispatcher = localDispatcher;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public void schedule(TaskAccepted task, TaskType taskType) {
    if (!governanceProperties.isEnabled()) {
      dispatchLocallyAfterCommit(task, taskType);
      return;
    }
    TravelPlanEntity plan =
        planRepository
            .findById(task.resourceId())
            .orElseThrow(() -> new IllegalStateException("PLAN_NOT_FOUND"));
    Instant now = clock.instant();
    PlanRequestedPayload payload =
        new PlanRequestedPayload(
            plan.getPlanId(),
            taskType,
            taskType == TaskType.PLAN_REPLAN ? "USER_REPLAN" : "CREATE",
            1,
            plan.getVersion(),
            planningProperties.getCandidateTarget(),
            List.of(plan.getStrategy().name()),
            plan.getPreviousPlanId(),
            now);
    EventEnvelope event =
        new EventEnvelope(
            UUID.randomUUID(),
            "trip.plan.requested",
            EventEnvelope.CURRENT_VERSION,
            now,
            task.taskId(),
            task.taskId(),
            task.traceId(),
            plan.getTripId(),
            task.taskId(),
            "planning-task-" + task.taskId(),
            objectMapper.valueToTree(payload));
    outboxService.enqueue(
        governanceProperties.getPlanRequestedTopic(),
        task.taskId().toString(),
        plan.getPlanId(),
        event);
  }

  private void dispatchLocallyAfterCommit(TaskAccepted task, TaskType taskType) {
    PlanningTaskDispatcher dispatcher = localDispatcher.getIfAvailable();
    if (dispatcher == null) {
      return;
    }
    Runnable dispatch = () -> dispatcher.dispatch(task.taskId(), task.resourceId(), taskType);
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
}
