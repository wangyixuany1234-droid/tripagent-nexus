package com.tripagent.nexus.integration.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.application.document.DocumentParseCoordinator;
import com.tripagent.nexus.application.service.EventInboxService;
import com.tripagent.nexus.application.service.EventInboxService.InboxClaim;
import com.tripagent.nexus.integration.document.DocumentParseEvents.Requested;
import com.tripagent.nexus.integration.document.DocumentParseEvents.RequestedPayload;
import com.tripagent.nexus.integration.messaging.AsyncOperationException;
import com.tripagent.nexus.integration.messaging.EventCausationContext;
import com.tripagent.nexus.integration.messaging.EventEnvelope;
import com.tripagent.nexus.integration.messaging.EventPayloadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "app.document-processing.messaging-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DocumentParseRequestedListener {

  private static final Logger log = LoggerFactory.getLogger(DocumentParseRequestedListener.class);
  private static final String CONSUMER_NAME = "document-parser-adapter-v1";

  private final ObjectMapper objectMapper;
  private final DocumentParseCoordinator coordinator;
  private final EventPayloadPolicy payloadPolicy;
  private final EventInboxService inboxService;
  private final EventCausationContext causationContext;

  public DocumentParseRequestedListener(
      ObjectMapper objectMapper,
      DocumentParseCoordinator coordinator,
      EventPayloadPolicy payloadPolicy,
      EventInboxService inboxService,
      EventCausationContext causationContext) {
    this.objectMapper = objectMapper;
    this.coordinator = coordinator;
    this.payloadPolicy = payloadPolicy;
    this.inboxService = inboxService;
    this.causationContext = causationContext;
  }

  @KafkaListener(
      topics = "${app.async-governance.document-parse-requested-topic:document.parse.requested}",
      groupId = "trip-agent-document-parser-adapter-v1",
      concurrency = "${app.async-governance.document-consumer-concurrency:2}")
  public void onMessage(String payload) {
    EventEnvelope event;
    try {
      event = objectMapper.readValue(payload, EventEnvelope.class);
      payloadPolicy.validate(event);
      if (!"document.parse.requested".equals(event.eventType())) {
        throw new AsyncOperationException(
            "EVENT_TYPE_UNSUPPORTED", false, "The document event type is not supported.");
      }
    } catch (JsonProcessingException exception) {
      throw new AsyncOperationException(
          "EVENT_MALFORMED", false, "The document event is malformed.", exception);
    }
    if (inboxService.claim(CONSUMER_NAME, event) == InboxClaim.DUPLICATE) {
      log.debug("Duplicate document event ignored eventId={}", event.eventId());
      return;
    }
    Requested requested =
        new Requested(
            event.eventId(),
            event.eventType(),
            event.schemaVersion(),
            event.occurredAt(),
            event.correlationId(),
            event.causationId(),
            event.traceId(),
            event.tripId(),
            event.taskId(),
            event.idempotencyKey(),
            objectMapper.convertValue(event.payload(), RequestedPayload.class));
    try {
      causationContext.run(event, () -> coordinator.process(requested));
      inboxService.complete(CONSUMER_NAME, event.eventId());
    } catch (RuntimeException exception) {
      AsyncOperationException failure =
          exception instanceof AsyncOperationException asyncException
              ? asyncException
              : new AsyncOperationException(
                  "DOCUMENT_PROCESSING_FAILED",
                  false,
                  "Document processing could not be completed.",
                  exception);
      inboxService.fail(CONSUMER_NAME, event.eventId(), failure.code(), failure.retryable());
      throw failure;
    }
  }
}
