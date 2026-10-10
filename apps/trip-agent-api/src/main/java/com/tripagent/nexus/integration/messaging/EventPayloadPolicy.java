package com.tripagent.nexus.integration.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class EventPayloadPolicy {

  private static final int MAX_TEXT_VALUE_LENGTH = 10_000;
  private static final Set<String> FORBIDDEN_FIELDS =
      Set.of(
          "authorization",
          "apikey",
          "api_key",
          "rawimage",
          "imagebytes",
          "filebytes",
          "base64",
          "prompt",
          "systemprompt",
          "ocrtext");

  private final ObjectMapper objectMapper;
  private final AsyncGovernanceProperties properties;

  public EventPayloadPolicy(ObjectMapper objectMapper, AsyncGovernanceProperties properties) {
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  public void validate(EventEnvelope envelope) {
    if (!EventEnvelope.CURRENT_VERSION.equals(envelope.schemaVersion())) {
      throw new AsyncOperationException(
          "EVENT_VERSION_UNSUPPORTED", false, "The event version is not supported.");
    }
    try {
      if (objectMapper.writeValueAsBytes(envelope).length > properties.getMaxEventBytes()) {
        throw new AsyncOperationException(
            "EVENT_PAYLOAD_TOO_LARGE", false, "The event exceeds the configured size limit.");
      }
    } catch (JsonProcessingException exception) {
      throw new AsyncOperationException(
          "EVENT_SERIALIZATION_FAILED", false, "The event could not be serialized.", exception);
    }
    inspect(envelope.payload());
  }

  private static void inspect(JsonNode node) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node.isTextual() && node.textValue().length() > MAX_TEXT_VALUE_LENGTH) {
      throw new AsyncOperationException(
          "EVENT_TEXT_TOO_LARGE", false, "The event contains an oversized text value.");
    }
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> entry : node.properties()) {
        String normalized =
            entry.getKey().replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        if (FORBIDDEN_FIELDS.contains(normalized)) {
          throw new AsyncOperationException(
              "EVENT_SENSITIVE_FIELD_FORBIDDEN",
              false,
              "The event contains a forbidden sensitive field.");
        }
        inspect(entry.getValue());
      }
    } else if (node.isArray()) {
      node.forEach(EventPayloadPolicy::inspect);
    }
  }
}
