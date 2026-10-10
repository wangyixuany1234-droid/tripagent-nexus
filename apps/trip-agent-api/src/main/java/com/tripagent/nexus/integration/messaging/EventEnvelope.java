package com.tripagent.nexus.integration.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
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
    JsonNode payload) {

  public static final String CURRENT_VERSION = "1.0.0";

  public EventEnvelope replay(
      UUID replayEventId, UUID replayCausationId, UUID replayTaskId, Instant now) {
    return new EventEnvelope(
        replayEventId,
        eventType,
        schemaVersion,
        now,
        correlationId,
        replayCausationId,
        traceId,
        tripId,
        replayTaskId,
        "manual-replay-" + replayEventId,
        payload);
  }
}
