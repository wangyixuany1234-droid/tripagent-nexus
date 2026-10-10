package com.tripagent.nexus.integration.planning;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tripagent.nexus.application.planning.PlanningWorkflowService;
import com.tripagent.nexus.application.service.EventInboxService;
import com.tripagent.nexus.application.service.EventInboxService.InboxClaim;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import com.tripagent.nexus.integration.messaging.EventCausationContext;
import com.tripagent.nexus.integration.messaging.EventEnvelope;
import com.tripagent.nexus.integration.messaging.EventPayloadPolicy;
import com.tripagent.nexus.integration.planning.PlanningEvents.PlanRequestedPayload;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlanRequestedListenerTest {

  @Mock private EventInboxService inboxService;
  @Mock private PlanningWorkflowService workflowService;

  @Test
  void duplicateKafkaDeliveryDoesNotGenerateThePlanTwice() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    EventPayloadPolicy policy =
        new EventPayloadPolicy(objectMapper, new AsyncGovernanceProperties());
    PlanRequestedListener listener =
        new PlanRequestedListener(
            objectMapper, policy, inboxService, new EventCausationContext(), workflowService);
    UUID taskId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    PlanRequestedPayload payload =
        new PlanRequestedPayload(
            planId,
            TaskType.PLAN_GENERATION,
            "CREATE",
            1,
            1,
            2,
            List.of("BALANCED"),
            null,
            Instant.now());
    EventEnvelope event =
        new EventEnvelope(
            UUID.randomUUID(),
            "trip.plan.requested",
            "1.0.0",
            Instant.now(),
            taskId,
            taskId,
            UUID.randomUUID().toString().replace("-", ""),
            UUID.randomUUID(),
            taskId,
            "plan-listener-test-key-0001",
            objectMapper.valueToTree(payload));
    when(inboxService.claim(eq("plan-orchestrator-v1"), any(EventEnvelope.class)))
        .thenReturn(InboxClaim.ACQUIRED)
        .thenReturn(InboxClaim.DUPLICATE);
    String value = objectMapper.writeValueAsString(event);

    listener.onMessage(value);
    listener.onMessage(value);

    verify(workflowService, times(1)).execute(taskId, planId, TaskType.PLAN_GENERATION);
    verify(inboxService, times(1)).complete("plan-orchestrator-v1", event.eventId());
  }
}
