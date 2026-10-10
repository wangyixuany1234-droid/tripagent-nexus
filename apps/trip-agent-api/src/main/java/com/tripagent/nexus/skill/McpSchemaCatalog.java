package com.tripagent.nexus.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class McpSchemaCatalog {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final Map<McpToolName, McpSchema.Tool> tools;

  public McpSchemaCatalog(ObjectMapper objectMapper) {
    this.tools = loadTools(objectMapper);
  }

  public McpSchema.Tool tool(McpToolName name) {
    McpSchema.Tool tool = tools.get(name);
    if (tool == null) {
      throw new IllegalArgumentException("Unknown MCP tool: " + name.value());
    }
    return tool;
  }

  public Map<McpToolName, McpSchema.Tool> tools() {
    return Map.copyOf(tools);
  }

  public Map<String, Object> outputSchema(McpToolName name) {
    return Map.copyOf(tool(name).outputSchema());
  }

  public boolean matches(McpToolName name, McpSchema.Tool advertised) {
    McpSchema.Tool expected = tool(name);
    return expected.name().equals(advertised.name())
        && expected.inputSchema().equals(advertised.inputSchema())
        && expected.outputSchema().equals(advertised.outputSchema())
        && expected.annotations().equals(advertised.annotations());
  }

  private static Map<McpToolName, McpSchema.Tool> loadTools(ObjectMapper objectMapper) {
    try (InputStream stream =
        McpSchemaCatalog.class.getResourceAsStream("/contracts/mcp-tools.schema.json")) {
      if (stream == null) {
        throw new IllegalStateException("MCP tool contract resource is missing.");
      }
      JsonNode contract = objectMapper.readTree(stream);
      JsonNode definitions = contract.path("$defs");
      JsonNode toolMetadata = contract.path("x-tools");
      Map<String, Object> outputSchema =
          objectMapper.convertValue(definitions.path("toolResult"), MAP_TYPE);
      Map<McpToolName, McpSchema.Tool> result = new EnumMap<>(McpToolName.class);
      for (McpToolName name : McpToolName.values()) {
        JsonNode metadata = toolMetadata.path(name.value());
        String inputRef = metadata.path("input").asText();
        String inputName = inputRef.substring(inputRef.lastIndexOf('/') + 1);
        Map<String, Object> inputSchema =
            objectMapper.convertValue(definitions.path(inputName), MAP_TYPE);
        boolean readOnly = metadata.path("readOnly").asBoolean();
        boolean idempotent = metadata.path("idempotent").asBoolean();
        result.put(
            name,
            McpSchema.Tool.builder(name.value(), inputSchema)
                .title(title(name))
                .description(description(name))
                .outputSchema(outputSchema)
                .annotations(
                    McpSchema.ToolAnnotations.builder()
                        .title(title(name))
                        .readOnlyHint(readOnly)
                        .destructiveHint(!readOnly)
                        .idempotentHint(idempotent)
                        .openWorldHint(isOpenWorld(name))
                        .returnDirect(false)
                        .build())
                .build());
      }
      return result;
    } catch (IOException exception) {
      throw new IllegalStateException("MCP tool contracts could not be loaded.", exception);
    }
  }

  private static String title(McpToolName name) {
    return switch (name) {
      case PLACE_SEARCH -> "Place search";
      case WEATHER_QUERY -> "Weather query";
      case ROUTE_CALCULATE -> "Route calculation";
      case EXCHANGE_RATE -> "Exchange rate";
      case OPENING_HOURS -> "Opening hours";
      case BOOKING_PARSE -> "Parsed booking lookup";
      case BUDGET_VALIDATE -> "Budget validation";
      case ITINERARY_STORE -> "Itinerary storage";
    };
  }

  private static String description(McpToolName name) {
    return switch (name) {
      case PLACE_SEARCH -> "Search evidence-backed travel places.";
      case WEATHER_QUERY -> "Query live or explicitly mocked weather data.";
      case ROUTE_CALCULATE -> "Calculate a route candidate and arrival time.";
      case EXCHANGE_RATE -> "Convert a monetary amount using a dated rate.";
      case OPENING_HOURS -> "Query opening hours for a place and date.";
      case BOOKING_PARSE -> "Read an existing server-side parsed booking document.";
      case BUDGET_VALIDATE -> "Deterministically total and validate budget line items.";
      case ITINERARY_STORE -> "Idempotently persist a validated itinerary artifact.";
    };
  }

  private static boolean isOpenWorld(McpToolName name) {
    return switch (name) {
      case PLACE_SEARCH, WEATHER_QUERY, ROUTE_CALCULATE, EXCHANGE_RATE, OPENING_HOURS -> true;
      case BOOKING_PARSE, BUDGET_VALIDATE, ITINERARY_STORE -> false;
    };
  }
}
