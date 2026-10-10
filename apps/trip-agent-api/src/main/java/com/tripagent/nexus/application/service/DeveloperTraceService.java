package com.tripagent.nexus.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.domain.model.Phase14Models.DeveloperTrace;
import com.tripagent.nexus.domain.model.Phase14Models.TraceSpan;
import com.tripagent.nexus.domain.model.Phase14Models.TraceSpanCategory;
import com.tripagent.nexus.infrastructure.config.DeveloperToolsProperties;
import com.tripagent.nexus.observability.SensitiveDataSanitizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class DeveloperTraceService {

  private static final int MAX_ATTRIBUTES = 30;

  private final RestClient restClient;
  private final DeveloperToolsProperties properties;

  public DeveloperTraceService(
      @Qualifier("jaegerRestClient") RestClient restClient, DeveloperToolsProperties properties) {
    this.restClient = restClient;
    this.properties = properties;
  }

  public void authorize(String actorRole) {
    if (!properties.isEnabled() || !"ADMIN".equals(actorRole)) {
      throw new ApiException(
          "DEVELOPER_TRACE_FORBIDDEN",
          HttpStatus.FORBIDDEN,
          "Developer trace access is not enabled.");
    }
  }

  public DeveloperTrace get(String traceId) {
    try {
      JsonNode root =
          restClient.get().uri("/api/traces/{traceId}", traceId).retrieve().body(JsonNode.class);
      JsonNode trace =
          root == null || !root.path("data").isArray() || root.path("data").isEmpty()
              ? null
              : root.path("data").get(0);
      if (trace == null) {
        throw notFound();
      }
      List<TraceSpan> spans = parseSpans(trace.path("spans"));
      if (spans.isEmpty()) {
        throw notFound();
      }
      long startMicros =
          spans.stream().mapToLong(span -> toMicros(span.startedAt())).min().orElse(0);
      long endMicros =
          spans.stream()
              .mapToLong(span -> toMicros(span.startedAt()) + span.durationMs() * 1000)
              .max()
              .orElse(startMicros);
      String status =
          spans.stream().anyMatch(span -> "ERROR".equals(span.status())) ? "ERROR" : "OK";
      return new DeveloperTrace(
          traceId,
          status,
          Instant.ofEpochMilli(startMicros / 1000),
          Math.max(0, (endMicros - startMicros) / 1000),
          spans);
    } catch (ApiException exception) {
      throw exception;
    } catch (RestClientResponseException exception) {
      if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
        throw notFound();
      }
      throw providerUnavailable();
    } catch (RestClientException exception) {
      throw providerUnavailable();
    }
  }

  private static List<TraceSpan> parseSpans(JsonNode nodes) {
    if (!nodes.isArray()) {
      return List.of();
    }
    List<TraceSpan> spans = new ArrayList<>();
    for (JsonNode node : nodes) {
      Map<String, String> attributes = attributes(node.path("tags"));
      long startMicros = node.path("startTime").asLong();
      long durationMicros = Math.max(0, node.path("duration").asLong());
      spans.add(
          new TraceSpan(
              node.path("spanID").asText(),
              parentSpanId(node.path("references")),
              category(node.path("operationName").asText(), attributes),
              safeText(node.path("operationName").asText(), 200),
              spanStatus(attributes),
              Instant.ofEpochMilli(startMicros / 1000),
              durationMicros / 1000,
              attributes));
    }
    return spans.stream()
        .sorted((left, right) -> left.startedAt().compareTo(right.startedAt()))
        .toList();
  }

  private static Map<String, String> attributes(JsonNode tags) {
    Map<String, String> values = new LinkedHashMap<>();
    if (!tags.isArray()) {
      return values;
    }
    for (JsonNode tag : tags) {
      if (values.size() >= MAX_ATTRIBUTES) {
        break;
      }
      String key = safeText(tag.path("key").asText(), 100);
      String raw = tag.path("value").asText();
      String normalized = key.toLowerCase(Locale.ROOT);
      String value =
          normalized.contains("prompt")
                  || normalized.contains("authorization")
                  || normalized.contains("api.key")
                  || normalized.contains("user.")
              ? "***"
              : safeText(SensitiveDataSanitizer.sanitize(raw), 500);
      values.put(key, value);
    }
    return Map.copyOf(values);
  }

  private static TraceSpanCategory category(String operation, Map<String, String> attributes) {
    String text = (operation + " " + attributes).toLowerCase(Locale.ROOT);
    if (text.contains("kafka") || text.contains("messaging")) {
      return TraceSpanCategory.KAFKA;
    }
    if (text.contains("mcp") || text.contains("tool.call")) {
      return TraceSpanCategory.MCP;
    }
    if (text.contains("a2a") || text.contains("delegat")) {
      return TraceSpanCategory.A2A;
    }
    if (text.contains("retrieval") || text.contains("rag") || text.contains("lancedb")) {
      return TraceSpanCategory.RAG;
    }
    if (text.contains("model") || text.contains("chat") || text.contains("gen_ai")) {
      return TraceSpanCategory.MODEL;
    }
    return TraceSpanCategory.AGENT;
  }

  private static String spanStatus(Map<String, String> attributes) {
    String status = attributes.getOrDefault("otel.status_code", "");
    boolean error =
        "ERROR".equalsIgnoreCase(status)
            || "true".equalsIgnoreCase(attributes.getOrDefault("error", "false"));
    return error ? "ERROR" : status.isBlank() ? "UNSET" : "OK";
  }

  private static String parentSpanId(JsonNode references) {
    if (!references.isArray() || references.isEmpty()) {
      return null;
    }
    String parent = references.get(0).path("spanID").asText();
    return parent.isBlank() ? null : parent;
  }

  private static String safeText(String value, int maxLength) {
    if (value == null) {
      return "";
    }
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }

  private static long toMicros(Instant instant) {
    return instant.toEpochMilli() * 1000;
  }

  private static ApiException notFound() {
    return new ApiException("TRACE_NOT_FOUND", HttpStatus.NOT_FOUND, "Trace data was not found.");
  }

  private static ApiException providerUnavailable() {
    return new ApiException(
        "TRACE_PROVIDER_UNAVAILABLE",
        HttpStatus.SERVICE_UNAVAILABLE,
        "Trace data is temporarily unavailable.");
  }
}
