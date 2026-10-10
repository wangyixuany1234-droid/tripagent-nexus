package com.tripagent.nexus.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.tripagent.nexus.integration.messaging.EventEnvelope;
import com.tripagent.nexus.skill.McpToolResult;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.junit.jupiter.api.Test;

class ProtocolJsonSchemaContractTest {

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final JsonSchemaFactory SCHEMA_FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

  @Test
  void kafkaEnvelopeMatchesThePublishedJsonSchema() {
    UUID taskId = UUID.randomUUID();
    EventEnvelope event =
        new EventEnvelope(
            UUID.randomUUID(),
            "trip.plan.requested",
            EventEnvelope.CURRENT_VERSION,
            Instant.parse("2026-10-01T00:00:00Z"),
            taskId,
            taskId,
            "0123456789abcdef0123456789abcdef",
            UUID.randomUUID(),
            taskId,
            "stage3-kafka-event-0001",
            OBJECT_MAPPER
                .createObjectNode()
                .put("planId", UUID.randomUUID().toString())
                .put("taskType", "PLAN_GENERATION"));

    JsonNode schema = loadContract("/contracts/events/kafka-event.schema.json");
    assertValid(schema, OBJECT_MAPPER.valueToTree(event));

    JsonNode unsupported = OBJECT_MAPPER.valueToTree(event);
    ((com.fasterxml.jackson.databind.node.ObjectNode) unsupported).put("schemaVersion", "99.0.0");
    assertThat(validate(schema, unsupported)).isNotEmpty();
  }

  @Test
  void everyMcpToolInputAndOutputMatchesItsJsonSchema() {
    JsonNode contract = loadContract("/contracts/mcp-tools.schema.json");
    Map<String, Object> samples =
        Map.of(
            "place_search",
            Map.of("query", "museum", "city", "Beijing", "maxResults", 5),
            "weather_query",
            Map.of("city", "Beijing", "startDate", "2026-10-01", "endDate", "2026-10-03"),
            "route_calculate",
            Map.of(
                "origin",
                "Forbidden City",
                "destination",
                "Jingshan Park",
                "departureTime",
                "2026-10-01T02:00:00Z",
                "modes",
                List.of("WALK")),
            "exchange_rate",
            Map.of(
                "amount",
                new BigDecimal("100.0000"),
                "fromCurrency",
                "CNY",
                "toCurrency",
                "USD",
                "asOf",
                "2026-10-01"),
            "opening_hours",
            Map.of("placeId", "place-001", "date", "2026-10-01"),
            "booking_parse",
            Map.of("documentId", UUID.randomUUID().toString()),
            "budget_validate",
            Map.of(
                "currency",
                "CNY",
                "budgetLimit",
                new BigDecimal("3000.0000"),
                "items",
                List.of(
                    Map.of(
                        "category",
                        "TICKET",
                        "description",
                        "Museum",
                        "amount",
                        new BigDecimal("60.0000"),
                        "currency",
                        "CNY"))),
            "itinerary_store",
            Map.of(
                "tripId",
                UUID.randomUUID().toString(),
                "planVersion",
                1,
                "itinerary",
                Map.of("days", List.of()),
                "idempotencyKey",
                "stage3-itinerary-0001"));

    contract
        .path("x-tools")
        .properties()
        .forEach(
            entry -> {
              String toolName = entry.getKey();
              String inputName = entry.getValue().path("input").asText().replace("#/$defs/", "");
              assertValid(contract.path("$defs").path(inputName), samples.get(toolName));
            });

    McpToolResult output =
        new McpToolResult(
            McpToolResult.Status.SUCCEEDED,
            McpToolResult.ProviderMode.MOCK,
            OBJECT_MAPPER.createObjectNode().put("result", "synthetic"),
            List.of());
    assertValid(contract.path("$defs").path("toolResult"), OBJECT_MAPPER.valueToTree(output));
  }

  @Test
  void emittedA2aMessageMatchesThePublishedJsonSchema() {
    UUID taskId = UUID.randomUUID();
    Message message =
        Message.builder()
            .role(Message.Role.ROLE_USER)
            .parts(
                new DataPart(
                    Map.of(
                        "taskId",
                        taskId.toString(),
                        "requestText",
                        "Create a synthetic Beijing itinerary")))
            .messageId(UUID.randomUUID().toString())
            .contextId(taskId.toString())
            .metadata(Map.of("agentName", "RequirementAgent"))
            .build();

    assertValid(
        loadContract("/contracts/a2a-message.schema.json"), OBJECT_MAPPER.valueToTree(message));
  }

  private static JsonNode loadContract(String resource) {
    try (InputStream stream = ProtocolJsonSchemaContractTest.class.getResourceAsStream(resource)) {
      assertThat(stream).as("contract resource %s", resource).isNotNull();
      return OBJECT_MAPPER.readTree(stream);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not load contract " + resource, exception);
    }
  }

  private static void assertValid(JsonNode schema, Object instance) {
    assertThat(validate(schema, OBJECT_MAPPER.valueToTree(instance))).isEmpty();
  }

  private static Set<ValidationMessage> validate(JsonNode schema, JsonNode instance) {
    JsonSchema jsonSchema = SCHEMA_FACTORY.getSchema(schema);
    return jsonSchema.validate(instance);
  }
}
