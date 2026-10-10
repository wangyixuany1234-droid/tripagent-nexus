package com.tripagent.nexus.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.controller.dto.ApiModels.TaskAccepted;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import com.tripagent.nexus.infrastructure.config.DocumentProcessingProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.DocumentEntity;
import com.tripagent.nexus.integration.document.DocumentParseEvents.CompletedPayload;
import com.tripagent.nexus.integration.document.DocumentParseEvents.EventIssue;
import com.tripagent.nexus.integration.document.DocumentParseEvents.Requested;
import com.tripagent.nexus.integration.document.DocumentParseEvents.RequestedPayload;
import com.tripagent.nexus.integration.messaging.EventEnvelope;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentOutboxService {

  private static final String SCHEMA_VERSION = "1.0.0";

  private final EventOutboxService eventOutboxService;
  private final DocumentProcessingProperties properties;
  private final AsyncGovernanceProperties governanceProperties;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public DocumentOutboxService(
      EventOutboxService eventOutboxService,
      DocumentProcessingProperties properties,
      AsyncGovernanceProperties governanceProperties,
      ObjectMapper objectMapper,
      Clock clock) {
    this.eventOutboxService = eventOutboxService;
    this.properties = properties;
    this.governanceProperties = governanceProperties;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public UUID enqueueRequested(DocumentEntity document, TaskAccepted task, String idempotencyKey) {
    Instant now = clock.instant();
    UUID eventId = UUID.randomUUID();
    UUID correlationId = task.taskId();
    RequestedPayload payload =
        new RequestedPayload(
            document.getDocumentId(),
            "BOOKING",
            document.getMediaType(),
            document.getSizeBytes(),
            document.getSha256(),
            "document://" + document.getDocumentId(),
            properties.getLocale(),
            SCHEMA_VERSION);
    EventEnvelope event =
        new EventEnvelope(
            eventId,
            "document.parse.requested",
            SCHEMA_VERSION,
            now,
            correlationId,
            task.taskId(),
            task.traceId(),
            document.getTripId(),
            task.taskId(),
            idempotencyKey,
            objectMapper.valueToTree(payload));
    return eventOutboxService.enqueue(
        governanceProperties.getDocumentParseRequestedTopic(),
        document.getTripId().toString(),
        document.getDocumentId(),
        event);
  }

  @Transactional
  public void enqueueCompleted(
      Requested request,
      BookingExtractionResult result,
      List<UUID> bookingIds,
      String parseStatus) {
    Instant now = clock.instant();
    UUID eventId = UUID.randomUUID();
    List<EventIssue> warnings =
        result.warnings().stream()
            .map(
                ignored ->
                    new EventIssue(
                        "PARSER_WARNING",
                        "WARNING",
                        "/bookings",
                        "Parser reported a non-fatal extraction warning."))
            .toList();
    CompletedPayload payload =
        new CompletedPayload(
            request.payload().documentId(),
            parseStatus,
            bookingIds,
            result.parserVersion(),
            warnings);
    EventEnvelope event =
        new EventEnvelope(
            eventId,
            "document.parse.completed",
            SCHEMA_VERSION,
            now,
            request.correlationId(),
            request.eventId(),
            request.traceId(),
            request.tripId(),
            request.taskId(),
            request.idempotencyKey(),
            objectMapper.valueToTree(payload));
    eventOutboxService.enqueue(
        governanceProperties.getDocumentParseCompletedTopic(),
        request.tripId().toString(),
        request.payload().documentId(),
        event);
  }
}
