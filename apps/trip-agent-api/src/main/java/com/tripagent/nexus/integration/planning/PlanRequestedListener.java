package com.tripagent.nexus.integration.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.application.planning.PlanningWorkflowService;
import com.tripagent.nexus.application.service.EventInboxService;
import com.tripagent.nexus.application.service.EventInboxService.InboxClaim;
import com.tripagent.nexus.integration.messaging.AsyncOperationException;
import com.tripagent.nexus.integration.messaging.EventCausationContext;
import com.tripagent.nexus.integration.messaging.EventEnvelope;
import com.tripagent.nexus.integration.messaging.EventPayloadPolicy;
import com.tripagent.nexus.integration.planning.PlanningEvents.PlanRequestedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(PlanningWorkflowService.class)
@ConditionalOnProperty(
    name = "app.async-governance.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PlanRequestedListener {

  private static final Logger log = LoggerFactory.getLogger(PlanRequestedListener.class);
  private static final String CONSUMER_NAME = "plan-orchestrator-v1";

  private final ObjectMapper objectMapper;
  private final EventPayloadPolicy payloadPolicy;
  private final EventInboxService inboxService;
  private final EventCausationContext causationContext;
  private final PlanningWorkflowService workflowService;

  public PlanRequestedListener(
      ObjectMapper objectMapper,
      EventPayloadPolicy payloadPolicy,
      EventInboxService inboxService,
      EventCausationContext causationContext,
      PlanningWorkflowService workflowService) {
    this.objectMapper = objectMapper;
    this.payloadPolicy = payloadPolicy;
    this.inboxService = inboxService;
    this.causationContext = causationContext;
    this.workflowService = workflowService;
  }

  @KafkaListener(
      topics = "${app.async-governance.plan-requested-topic:trip.plan.requested}",
      groupId = "trip-agent-orchestrator-v1",
      concurrency = "${app.async-governance.plan-consumer-concurrency:3}")
  public void onMessage(String value) {
    EventEnvelope event = parse(value);
    if (!"trip.plan.requested".equals(event.eventType())) {
      throw new AsyncOperationException(
          "EVENT_TYPE_UNSUPPORTED", false, "The planning event type is not supported.");
    }
    if (inboxService.claim(CONSUMER_NAME, event) == InboxClaim.DUPLICATE) {
      log.debug("Duplicate planning event ignored eventId={}", event.eventId());
      return;
    }
    try {
      PlanRequestedPayload payload =
          objectMapper.convertValue(event.payload(), PlanRequestedPayload.class);
      if (payload.planId() == null || payload.taskType() == null) {
        throw new AsyncOperationException(
            "PLAN_EVENT_INVALID", false, "The planning event is missing required references.");
      }
      causationContext.run(
          event,
          () -> workflowService.execute(event.taskId(), payload.planId(), payload.taskType()));
      inboxService.complete(CONSUMER_NAME, event.eventId());
    } catch (RuntimeException exception) {
      AsyncOperationException failure =
          exception instanceof AsyncOperationException asyncException
              ? asyncException
              : new AsyncOperationException(
                  "PLAN_EVENT_PROCESSING_FAILED",
                  false,
                  "The planning event could not be completed.",
                  exception);
      inboxService.fail(CONSUMER_NAME, event.eventId(), failure.code(), failure.retryable());
      throw failure;
    }
  }

  private EventEnvelope parse(String value) {
    try {
      EventEnvelope event = objectMapper.readValue(value, EventEnvelope.class);
      payloadPolicy.validate(event);
      return event;
    } catch (JsonProcessingException exception) {
      throw new AsyncOperationException(
          "EVENT_MALFORMED", false, "The planning event is malformed.", exception);
    }
  }
}
