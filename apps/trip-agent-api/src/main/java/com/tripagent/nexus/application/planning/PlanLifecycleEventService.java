package com.tripagent.nexus.application.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripagent.nexus.application.service.EventOutboxService;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStatus;
import com.tripagent.nexus.domain.model.TravelPlan;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelPlanRepository;
import com.tripagent.nexus.integration.messaging.EventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanLifecycleEventService {

  private final TravelPlanRepository planRepository;
  private final TravelPlanMapper planMapper;
  private final EventOutboxService outboxService;
  private final AsyncGovernanceProperties properties;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public PlanLifecycleEventService(
      TravelPlanRepository planRepository,
      TravelPlanMapper planMapper,
      EventOutboxService outboxService,
      AsyncGovernanceProperties properties,
      ObjectMapper objectMapper,
      Clock clock) {
    this.planRepository = planRepository;
    this.planMapper = planMapper;
    this.outboxService = outboxService;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public void enqueueCompleted(
      UUID taskId,
      UUID tripId,
      String traceId,
      List<UUID> planIds,
      List<String> degradedComponents) {
    if (!properties.isEnabled()) {
      return;
    }
    ArrayNode candidates = objectMapper.createArrayNode();
    for (UUID planId : planIds) {
      TravelPlan plan =
          planRepository
              .findById(planId)
              .map(planMapper::toDomain)
              .orElseThrow(() -> new IllegalStateException("PLAN_NOT_FOUND"));
      ObjectNode amount = objectMapper.createObjectNode();
      amount.put("amount", plan.budgetSummary().estimatedTotal().toPlainString());
      amount.put("currency", plan.budgetSummary().currency());
      ObjectNode candidate = objectMapper.createObjectNode();
      candidate.put("planId", plan.planId().toString());
      candidate.put("planFamilyId", plan.planFamilyId().toString());
      candidate.put("itineraryVersionId", plan.planId().toString());
      if (plan.previousPlanId() != null) {
        candidate.put("previousPlanId", plan.previousPlanId().toString());
      }
      candidate.put("version", plan.version());
      candidate.put("candidateKey", plan.alternativeKey());
      candidate.put("strategy", plan.strategy().name());
      candidate.put(
          "validationStatus",
          plan.status() == PlanStatus.PARTIAL ? "VALID_WITH_WARNINGS" : "VALID");
      candidate.set("totalAmount", amount);
      candidates.add(candidate);
    }
    Instant now = clock.instant();
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put(
        "completionStatus", degradedComponents.isEmpty() ? "COMPLETED" : "PARTIALLY_COMPLETED");
    payload.set("candidates", candidates);
    ArrayNode gaps = objectMapper.createArrayNode();
    if (degradedComponents.contains("RETRIEVAL")) {
      gaps.add("SOURCE_UNAVAILABLE");
    }
    if (degradedComponents.contains("AGENT_ORCHESTRATION")) {
      gaps.add("CANDIDATE_TARGET_NOT_MET");
    }
    payload.set("gapCodes", gaps);
    payload.put("completedAt", now.toString());
    EventEnvelope event =
        new EventEnvelope(
            UUID.randomUUID(),
            "plan.completed",
            EventEnvelope.CURRENT_VERSION,
            now,
            taskId,
            taskId,
            traceId,
            tripId,
            taskId,
            "plan-completed-" + taskId,
            payload);
    outboxService.enqueue(properties.getPlanCompletedTopic(), taskId.toString(), taskId, event);
  }
}
