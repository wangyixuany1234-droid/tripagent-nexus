package com.tripagent.nexus.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripagent.nexus.application.service.DocumentApplicationService;
import com.tripagent.nexus.infrastructure.config.McpProperties;
import com.tripagent.nexus.infrastructure.config.McpProperties.ProviderMode;
import com.tripagent.nexus.skill.ItineraryArtifactService.McpToolException;
import com.tripagent.nexus.skill.McpToolResult.Status;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "app.agents.enabled", havingValue = "true", matchIfMissing = true)
public class McpToolCatalog {

  private final ObjectMapper objectMapper;
  private final RestClient retrievalClient;
  private final DocumentApplicationService documentService;
  private final ItineraryArtifactService itineraryService;
  private final McpResourceAuthorizer resourceAuthorizer;
  private final McpProperties properties;

  public McpToolCatalog(
      ObjectMapper objectMapper,
      @Qualifier("aiRetrievalRestClient") RestClient retrievalClient,
      DocumentApplicationService documentService,
      ItineraryArtifactService itineraryService,
      McpResourceAuthorizer resourceAuthorizer,
      McpProperties properties) {
    this.objectMapper = objectMapper;
    this.retrievalClient = retrievalClient;
    this.documentService = documentService;
    this.itineraryService = itineraryService;
    this.resourceAuthorizer = resourceAuthorizer;
    this.properties = properties;
  }

  public McpToolResult execute(
      McpToolName toolName, Map<String, Object> arguments, McpCallContext context) {
    return switch (toolName) {
      case PLACE_SEARCH -> placeSearch(arguments);
      case WEATHER_QUERY -> weatherQuery(arguments);
      case ROUTE_CALCULATE -> routeCalculate(arguments);
      case EXCHANGE_RATE -> exchangeRate(arguments);
      case OPENING_HOURS -> openingHours(arguments);
      case BOOKING_PARSE -> bookingParse(arguments, context);
      case BUDGET_VALIDATE -> budgetValidate(arguments);
      case ITINERARY_STORE -> itineraryStore(arguments, context);
    };
  }

  public McpToolResult execute(McpToolName toolName, Map<String, Object> arguments) {
    return execute(toolName, arguments, null);
  }

  private McpToolResult placeSearch(Map<String, Object> arguments) {
    if (properties.getProviderMode() == ProviderMode.MOCK) {
      ObjectNode data = objectMapper.createObjectNode();
      data.put("query", requiredText(arguments, "query"));
      data.put("city", requiredText(arguments, "city"));
      data.putArray("places")
          .addObject()
          .put("placeId", "mock-place-1")
          .put("name", "Mock place")
          .put("latitude", "39.9163447")
          .put("longitude", "116.3971546")
          .put("sourceId", "mock-source-place-1");
      return success(McpToolResult.ProviderMode.MOCK, data, "Mock MCP provider is active.");
    }

    Map<String, Object> request =
        Map.of(
            "query",
            requiredText(arguments, "query"),
            "city",
            requiredText(arguments, "city"),
            "language",
            "zh-CN",
            "includePublicKnowledge",
            true,
            "includeUserMemory",
            false,
            "includeGraph",
            true,
            "maxResults",
            integer(arguments, "maxResults", 10));
    try {
      JsonNode response =
          retrievalClient
              .post()
              .uri("/internal/v1/retrieval/search")
              .body(request)
              .retrieve()
              .body(JsonNode.class);
      return success(McpToolResult.ProviderMode.LIVE, requiredNode(response), null);
    } catch (RestClientException exception) {
      return unavailable("PLACE_PROVIDER_UNAVAILABLE");
    }
  }

  private McpToolResult weatherQuery(Map<String, Object> arguments) {
    if (properties.getProviderMode() != ProviderMode.MOCK) {
      return unavailable("WEATHER_PROVIDER_NOT_CONFIGURED");
    }
    ObjectNode data = objectMapper.createObjectNode();
    data.put("city", requiredText(arguments, "city"));
    data.put("startDate", requiredText(arguments, "startDate"));
    data.put("endDate", requiredText(arguments, "endDate"));
    data.put("forecast", "Mock weather; do not present as live data.");
    return success(McpToolResult.ProviderMode.MOCK, data, "Mock MCP provider is active.");
  }

  private McpToolResult routeCalculate(Map<String, Object> arguments) {
    if (properties.getProviderMode() != ProviderMode.MOCK) {
      return unavailable("ROUTE_PROVIDER_NOT_CONFIGURED");
    }
    OffsetDateTime departure = OffsetDateTime.parse(requiredText(arguments, "departureTime"));
    ObjectNode data = objectMapper.createObjectNode();
    data.put("origin", requiredText(arguments, "origin"));
    data.put("destination", requiredText(arguments, "destination"));
    data.put("departureTime", departure.toString());
    data.put("arrivalTime", departure.plusMinutes(45).toString());
    data.put("durationMinutes", 45);
    data.put("estimatedCost", "12.0000");
    data.put("currency", "CNY");
    return success(McpToolResult.ProviderMode.MOCK, data, "Mock MCP provider is active.");
  }

  private McpToolResult exchangeRate(Map<String, Object> arguments) {
    BigDecimal amount = decimal(arguments, "amount");
    String from = requiredText(arguments, "fromCurrency");
    String to = requiredText(arguments, "toCurrency");
    if (from.equals(to)) {
      ObjectNode data = objectMapper.createObjectNode();
      data.put("amount", amount);
      data.put("convertedAmount", amount);
      data.put("rate", BigDecimal.ONE);
      data.put("fromCurrency", from);
      data.put("toCurrency", to);
      return success(McpToolResult.ProviderMode.INTERNAL, data, null);
    }
    if (properties.getProviderMode() != ProviderMode.MOCK) {
      return unavailable("EXCHANGE_RATE_PROVIDER_NOT_CONFIGURED");
    }
    BigDecimal rate = new BigDecimal("0.1400");
    ObjectNode data = objectMapper.createObjectNode();
    data.put("amount", amount);
    data.put("convertedAmount", amount.multiply(rate).setScale(4, RoundingMode.HALF_UP));
    data.put("rate", rate);
    data.put("fromCurrency", from);
    data.put("toCurrency", to);
    return success(McpToolResult.ProviderMode.MOCK, data, "Mock MCP provider is active.");
  }

  private McpToolResult openingHours(Map<String, Object> arguments) {
    if (properties.getProviderMode() != ProviderMode.MOCK) {
      return unavailable("OPENING_HOURS_PROVIDER_NOT_CONFIGURED");
    }
    ObjectNode data = objectMapper.createObjectNode();
    data.put("placeId", requiredText(arguments, "placeId"));
    data.put("date", requiredText(arguments, "date"));
    data.put("open", true);
    data.put("opensAt", "09:00");
    data.put("closesAt", "17:00");
    return success(McpToolResult.ProviderMode.MOCK, data, "Mock MCP provider is active.");
  }

  private McpToolResult bookingParse(Map<String, Object> arguments, McpCallContext context) {
    UUID documentId = UUID.fromString(requiredText(arguments, "documentId"));
    resourceAuthorizer.requireDocument(context, documentId);
    JsonNode data = objectMapper.valueToTree(documentService.get(documentId));
    return success(McpToolResult.ProviderMode.INTERNAL, data, null);
  }

  private McpToolResult budgetValidate(Map<String, Object> arguments) {
    String currency = requiredText(arguments, "currency");
    BigDecimal budgetLimit = decimal(arguments, "budgetLimit");
    JsonNode items = objectMapper.valueToTree(arguments.get("items"));
    if (!items.isArray()) {
      throw new McpToolException("BUDGET_ITEMS_INVALID", "Budget items must be an array.");
    }
    BigDecimal total = BigDecimal.ZERO;
    ArrayNode mismatches = objectMapper.createArrayNode();
    for (JsonNode item : items) {
      String itemCurrency = item.path("currency").asText();
      if (!currency.equals(itemCurrency)) {
        mismatches.add(item.path("description").asText("unknown"));
        continue;
      }
      total = total.add(item.path("amount").decimalValue());
    }
    total = total.setScale(4, RoundingMode.HALF_UP);
    ObjectNode data = objectMapper.createObjectNode();
    data.put("currency", currency);
    data.put("total", total);
    data.put("budgetLimit", budgetLimit);
    data.put("overrun", total.subtract(budgetLimit).max(BigDecimal.ZERO));
    data.put("withinBudget", total.compareTo(budgetLimit) <= 0 && mismatches.isEmpty());
    data.set("currencyMismatches", mismatches);
    return success(McpToolResult.ProviderMode.INTERNAL, data, null);
  }

  private McpToolResult itineraryStore(Map<String, Object> arguments, McpCallContext context) {
    UUID tripId = UUID.fromString(requiredText(arguments, "tripId"));
    resourceAuthorizer.requireTrip(context, tripId);
    var stored =
        itineraryService.store(
            tripId,
            integer(arguments, "planVersion", 1),
            requiredText(arguments, "idempotencyKey"),
            requiredNode(objectMapper.valueToTree(arguments.get("itinerary"))));
    return success(McpToolResult.ProviderMode.INTERNAL, objectMapper.valueToTree(stored), null);
  }

  private McpToolResult success(McpToolResult.ProviderMode mode, JsonNode data, String warning) {
    return new McpToolResult(
        Status.SUCCEEDED, mode, data, warning == null ? List.of() : List.of(warning));
  }

  private McpToolResult unavailable(String code) {
    ObjectNode data = objectMapper.createObjectNode();
    data.put("code", code);
    return new McpToolResult(
        Status.UNAVAILABLE,
        McpToolResult.ProviderMode.LIVE,
        data,
        List.of("No live provider is configured for this tool."));
  }

  private static String requiredText(Map<String, Object> values, String key) {
    Object value = values.get(key);
    if (value == null || value.toString().isBlank()) {
      throw new McpToolException("MCP_ARGUMENT_INVALID", key + " is required.");
    }
    return value.toString();
  }

  private static int integer(Map<String, Object> values, String key, int defaultValue) {
    Object value = values.get(key);
    return value == null ? defaultValue : Integer.parseInt(value.toString());
  }

  private static BigDecimal decimal(Map<String, Object> values, String key) {
    return new BigDecimal(requiredText(values, key));
  }

  private static JsonNode requiredNode(JsonNode value) {
    if (value == null || value.isNull()) {
      throw new McpToolException("MCP_TOOL_EMPTY_RESULT", "Tool returned no data.");
    }
    return value;
  }
}
