package com.tripagent.nexus.integration.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DocumentParseEvents {

  private DocumentParseEvents() {}

  public record Requested(
      UUID eventId,
      String eventType,
      String schemaVersion,
      Instant occurredAt,
      UUID correlationId,
      UUID causationId,
      String traceId,
      UUID tripId,
      UUID taskId,
      String idempotencyKey,
      RequestedPayload payload) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RequestedPayload(
      UUID documentId,
      String documentType,
      String mediaType,
      long sizeBytes,
      String sha256,
      String sourceRef,
      String locale,
      String parserSchemaVersion) {}

  public record Completed(
      UUID eventId,
      String eventType,
      String schemaVersion,
      Instant occurredAt,
      UUID correlationId,
      UUID causationId,
      String traceId,
      UUID tripId,
      UUID taskId,
      String idempotencyKey,
      CompletedPayload payload) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CompletedPayload(
      UUID documentId,
      String parseStatus,
      List<UUID> extractionIds,
      String parserVersion,
      List<EventIssue> warnings) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EventIssue(String code, String severity, String field, String message) {}
}
