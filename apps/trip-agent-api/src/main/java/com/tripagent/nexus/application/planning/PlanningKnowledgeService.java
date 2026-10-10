package com.tripagent.nexus.application.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripagent.nexus.agent.AgentContracts.AgentEvidence;
import com.tripagent.nexus.application.task.RedisRuntimeStateService;
import com.tripagent.nexus.domain.model.TravelRequirement;
import com.tripagent.nexus.guardrail.UntrustedContentGuard;
import com.tripagent.nexus.infrastructure.config.PlanningProperties;
import com.tripagent.nexus.infrastructure.config.PlanningProperties.ProviderMode;
import com.tripagent.nexus.integration.messaging.AsyncOperationException;
import com.tripagent.nexus.integration.messaging.ExternalCallExecutor;
import com.tripagent.nexus.observability.TraceOperations;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
@ConditionalOnProperty(name = "app.planning.enabled", havingValue = "true", matchIfMissing = true)
public class PlanningKnowledgeService {

  private final RestClient retrievalClient;
  private final PlanningProperties properties;
  private final ExternalCallExecutor callExecutor;
  private final RedisRuntimeStateService runtimeState;
  private final TraceOperations traces;
  private final UntrustedContentGuard contentGuard;

  public PlanningKnowledgeService(
      @Qualifier("aiRetrievalRestClient") RestClient retrievalClient,
      PlanningProperties properties,
      ExternalCallExecutor callExecutor,
      RedisRuntimeStateService runtimeState,
      TraceOperations traces,
      UntrustedContentGuard contentGuard) {
    this.retrievalClient = retrievalClient;
    this.properties = properties;
    this.callExecutor = callExecutor;
    this.runtimeState = runtimeState;
    this.traces = traces;
    this.contentGuard = contentGuard;
  }

  public KnowledgeResult search(UUID userId, TravelRequirement requirement) {
    return traces.trace(
        "rag.retrieval",
        "tripagent.rag.retrieval",
        Map.of(
            "retrieval.operation",
            "planning",
            "retrieval.provider.mode",
            properties.getProviderMode().name()),
        () -> searchInternal(userId, requirement),
        (span, result) -> {
          span.tag("retrieval.result.count", Integer.toString(result.evidence().size()));
          span.tag(
              "retrieval.evidence_insufficient", Boolean.toString(result.evidenceInsufficient()));
        });
  }

  private KnowledgeResult searchInternal(UUID userId, TravelRequirement requirement) {
    if (properties.getProviderMode() == ProviderMode.MOCK) {
      return mockResult();
    }
    String city = requirement.destinations().getFirst().name();
    Map<String, Object> request =
        Map.ofEntries(
            Map.entry(
                "query",
                "Plan " + city + " with interests " + String.join(", ", requirement.interests())),
            Map.entry("city", city),
            Map.entry("travelDate", requirement.startDate().toString()),
            Map.entry("language", "zh-CN"),
            Map.entry("includePublicKnowledge", true),
            Map.entry("includeUserMemory", true),
            Map.entry("includeGraph", true),
            Map.entry("maxResults", 12));
    try {
      JsonNode response =
          callExecutor.execute(
              "planning-retrieval",
              2,
              () ->
                  retrievalClient
                      .post()
                      .uri("/internal/v1/retrieval/search")
                      .header("X-Tenant-Id", properties.getTenantId().toString())
                      .header("X-User-Id", userId.toString())
                      .header(HttpHeaders.ACCEPT, "application/json")
                      .body(request)
                      .retrieve()
                      .body(JsonNode.class));
      if (response == null) {
        return new KnowledgeResult(List.of(), true, List.of("RETRIEVAL_EMPTY_RESULT"));
      }
      List<AgentEvidence> evidence = new ArrayList<>();
      List<String> warnings = new ArrayList<>();
      for (JsonNode item : response.path("evidence")) {
        var isolated = contentGuard.isolate(item.path("excerpt").asText());
        if (isolated.isEmpty()) {
          warnings.add("RETRIEVAL_CONTENT_UNTRUSTED");
          continue;
        }
        evidence.add(
            new AgentEvidence(
                item.path("sourceId").asText(),
                item.path("sourceType").asText(),
                item.path("title").asText(),
                isolated.get(),
                item.path("relevanceScore").decimalValue(),
                item.path("fromRealtimeTool").asBoolean()));
      }
      response.path("warnings").forEach(item -> warnings.add(item.asText()));
      runtimeState.cacheHotPlace(
          city + ":" + requirement.startDate(), objectPublicEvidence(response.path("evidence")));
      return new KnowledgeResult(
          List.copyOf(evidence),
          response.path("evidenceInsufficient").asBoolean(evidence.isEmpty()),
          List.copyOf(warnings));
    } catch (RestClientException | AsyncOperationException exception) {
      return new KnowledgeResult(List.of(), true, List.of("RETRIEVAL_PROVIDER_UNAVAILABLE"));
    }
  }

  private static JsonNode objectPublicEvidence(JsonNode evidence) {
    com.fasterxml.jackson.databind.node.ArrayNode publicEvidence =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    evidence.forEach(
        item -> {
          if ("PUBLIC_KNOWLEDGE".equals(item.path("sourceType").asText())) {
            publicEvidence.add(item);
          }
        });
    return publicEvidence;
  }

  private static KnowledgeResult mockResult() {
    return new KnowledgeResult(
        List.of(
            new AgentEvidence(
                "mock-public-beijing-history",
                "PUBLIC_KNOWLEDGE",
                "Beijing historic core (MOCK)",
                "Dongcheng clusters the Forbidden City and nearby historic attractions.",
                new BigDecimal("0.9300"),
                false),
            new AgentEvidence(
                "mock-user-memory-local-food",
                "USER_MEMORY",
                "User dining preference (MOCK)",
                "The user previously preferred local cuisine near the current activity area.",
                new BigDecimal("0.8800"),
                false)),
        false,
        List.of("PLANNING_RETRIEVAL_MOCK"));
  }

  public record KnowledgeResult(
      List<AgentEvidence> evidence, boolean evidenceInsufficient, List<String> warnings) {

    public KnowledgeResult {
      evidence = List.copyOf(evidence);
      warnings = List.copyOf(warnings);
    }
  }
}
