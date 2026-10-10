package com.tripagent.nexus.agent;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.RefreshMode.BEFORE_EACH_TEST_METHOD;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AgentContracts.AgentResultEnvelope;
import com.tripagent.nexus.agent.AgentContracts.BookingContext;
import com.tripagent.nexus.agent.AgentContracts.ConstraintKind;
import com.tripagent.nexus.agent.AgentContracts.UserConstraint;
import com.tripagent.nexus.agent.a2a.A2ADelegationService;
import com.tripagent.nexus.agent.a2a.AgentRegistry;
import com.tripagent.nexus.agent.coordinator.CoordinatorAgentModels;
import com.tripagent.nexus.infrastructure.config.McpProperties;
import com.tripagent.nexus.skill.McpJsonSchemaValidator;
import com.tripagent.nexus.skill.McpToolGateway;
import com.tripagent.nexus.skill.McpToolName;
import com.tripagent.nexus.skill.McpToolResult;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.mcp.service-token=test-mcp-token",
      "app.mcp.provider-mode=MOCK",
      "app.agents.enabled=true",
      "app.health.kafka.enabled=false",
      "app.health.ai-retrieval.enabled=false"
    })
@ActiveProfiles({"test", "mock-model"})
@AutoConfigureEmbeddedDatabase(
    provider = EMBEDDED,
    type = POSTGRES,
    refresh = BEFORE_EACH_TEST_METHOD)
class AgentOrchestrationIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private AgentRegistry registry;
  @Autowired private A2ADelegationService delegationService;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private McpProperties mcpProperties;
  @Autowired private RecordingMcpToolGateway recordingToolGateway;

  @Test
  void officialMcpTransportListsAndCallsTypedTools() {
    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder()
            .header("Authorization", "Bearer " + mcpProperties.getServiceToken())
            .header("X-Agent-Name", AgentName.BUDGET.cardName());
    var transport =
        HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
            .endpoint("/mcp")
            .requestBuilder(requestBuilder)
            .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
            .build();

    try (McpSyncClient client =
        McpClient.sync(transport)
            .jsonSchemaValidator(new McpJsonSchemaValidator(objectMapper))
            .clientInfo(McpSchema.Implementation.builder("phase-6-test", "1.0.0").build())
            .build()) {
      client.initialize();
      assertThat(client.listTools().tools()).hasSize(8);

      McpSchema.CallToolResult result =
          client.callTool(
              McpSchema.CallToolRequest.builder("budget_validate")
                  .arguments(
                      Map.of(
                          "currency",
                          "CNY",
                          "budgetLimit",
                          new BigDecimal("100.0000"),
                          "items",
                          List.of(
                              Map.of(
                                  "category",
                                  "FOOD",
                                  "description",
                                  "Dinner",
                                  "amount",
                                  new BigDecimal("80.0000"),
                                  "currency",
                                  "CNY"))))
                  .build());

      JsonNode content = objectMapper.valueToTree(result.structuredContent());
      assertThat(result.isError()).isFalse();
      assertThat(content.path("status").asText()).isEqualTo("SUCCEEDED");
      assertThat(content.path("providerMode").asText()).isEqualTo("INTERNAL");
      assertThat(content.path("data").path("total").decimalValue()).isEqualByComparingTo("80.0000");
    }
  }

  @Test
  void coordinatorDiscoversAgentsAndTransfersArrivalAndCosts() {
    recordingToolGateway.clear();
    assertThat(registry.registeredAgents()).containsExactlyInAnyOrder(AgentName.values());
    assertThat(delegationService.discover(Set.of("route_calculate")))
        .extracting(card -> card.name())
        .contains("TransportAgent", "AccommodationAgent", "RecommendationAgent", "ReviewAgent");

    UUID taskId = UUID.randomUUID();
    ContextEnvelope context =
        new ContextEnvelope(
            List.of("All monetary totals are computed by Java."),
            List.of(
                new UserConstraint(
                    "budget", ConstraintKind.HARD, "Total budget is CNY 3000.", true)),
            List.of(
                new BookingContext(
                    UUID.randomUUID(),
                    "TRAIN",
                    Instant.parse("2026-10-01T00:00:00Z"),
                    Instant.parse("2026-10-01T02:00:00Z"),
                    new BigDecimal("500.0000"),
                    "CNY",
                    true,
                    "Confirmed arrival booking")),
            List.of(),
            List.of(),
            List.of(),
            1,
            8000,
            List.of());

    AgentResultEnvelope<JsonNode> result =
        delegationService.delegate(
            AgentName.COORDINATOR,
            new CoordinatorAgentModels.Input(
                taskId, context, "Plan a three-day Beijing culture trip."),
            "tenant-a:user-1");

    assertThat(result.status()).isEqualTo(AgentStatus.SUCCEEDED);
    assertThat(result.taskId()).isNotEqualTo(taskId);
    assertThat(result.result().path("delegatedTasks")).hasSize(7);
    CoordinatorAgentModels.Output output =
        objectMapper.convertValue(result.result(), CoordinatorAgentModels.Output.class);
    assertThat(output.delegatedTasks())
        .extracting(CoordinatorAgentModels.DelegatedTask::agentName)
        .containsExactly(
            AgentName.REQUIREMENT,
            AgentName.RESEARCH,
            AgentName.TRANSPORT,
            AgentName.ACCOMMODATION,
            AgentName.RECOMMENDATION,
            AgentName.BUDGET,
            AgentName.REVIEW);
    assertThat(output.alternatives())
        .extracting(CoordinatorAgentModels.PlanAlternative::candidateKey)
        .containsExactly("balanced", "low-transit");
    assertThat(
            delegationService
                .taskStatus(AgentName.COORDINATOR, result.taskId().toString(), "tenant-a:user-1")
                .status()
                .state())
        .isEqualTo(TaskState.TASK_STATE_COMPLETED);
    output
        .delegatedTasks()
        .forEach(
            delegated ->
                assertThat(
                        delegationService
                            .taskStatus(
                                delegated.agentName(),
                                delegated.taskId().toString(),
                                "planning-task:" + taskId)
                            .status()
                            .state())
                    .isEqualTo(TaskState.TASK_STATE_COMPLETED));
    JsonNode plan = result.result().path("plan");
    assertThat(plan.path("accommodation").path("arrivalAnchor").asText())
        .isEqualTo(plan.path("transport").path("options").get(0).path("arrivalTime").asText());
    assertThat(plan.path("budget").path("total").decimalValue()).isEqualByComparingTo("1960.0000");
    assertThat(result.traceId()).matches("^[0-9a-f]{32}$");
    assertThat(recordingToolGateway.calls())
        .containsExactlyInAnyOrder(
            new ToolCall(AgentName.RESEARCH, McpToolName.PLACE_SEARCH),
            new ToolCall(AgentName.TRANSPORT, McpToolName.ROUTE_CALCULATE),
            new ToolCall(AgentName.ACCOMMODATION, McpToolName.PLACE_SEARCH),
            new ToolCall(AgentName.RECOMMENDATION, McpToolName.PLACE_SEARCH),
            new ToolCall(AgentName.BUDGET, McpToolName.BUDGET_VALIDATE));
  }

  @Test
  void agentCardsPoliciesAndPromptsMatchVersionedContracts() throws IOException {
    for (AgentName name : AgentName.values()) {
      AgentDefinition<?, ?> definition = registry.require(name).agent().definition();
      String contractName =
          name.cardName().replace("Agent", "").toLowerCase(Locale.ROOT) + "-agent.schema.json";
      JsonNode cardContract =
          objectMapper
              .readTree(new ClassPathResource("contracts/agents/" + contractName).getInputStream())
              .path("x-agent-card");

      assertThat(definition.card().name()).isEqualTo(cardContract.path("name").asText());
      assertThat(definition.card().version()).isEqualTo(cardContract.path("version").asText());
      assertThat(definition.card().skills())
          .extracting(skill -> skill.id())
          .containsExactlyInAnyOrderElementsOf(textValues(cardContract.path("skills")));
      assertThat(definition.skillAllowlist())
          .extracting(tool -> tool.value())
          .containsExactlyInAnyOrderElementsOf(textValues(cardContract.path("skills")));
      assertThat(definition.policy().timeout().toMillis())
          .isEqualTo(cardContract.path("timeoutMs").asLong());
      assertThat(definition.policy().retryPolicy().maxAttempts())
          .isEqualTo(cardContract.path("retry").path("maxAttempts").asInt());
      assertThat(definition.policy().retryPolicy().initialBackoff().toMillis())
          .isEqualTo(cardContract.path("retry").path("initialBackoffMs").asLong());
      assertThat(definition.policy().errorCodes())
          .containsExactlyInAnyOrderElementsOf(textValues(cardContract.path("errorCodes")));
      assertThat(definition.policy().allowDegraded())
          .isEqualTo(cardContract.path("allowDegraded").asBoolean());
      assertThat(definition.policy().promptTemplateVersion())
          .isEqualTo(cardContract.path("promptTemplateVersion").asText());

      ClassPathResource prompt =
          new ClassPathResource(
              "prompts/agents/" + definition.policy().promptTemplateVersion() + ".txt");
      assertThat(prompt.exists()).isTrue();
      assertThat(prompt.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).isNotBlank();
    }
  }

  private static List<String> textValues(JsonNode array) {
    List<String> values = new java.util.ArrayList<>();
    array.forEach(value -> values.add(value.asText()));
    return List.copyOf(values);
  }

  @TestConfiguration
  static class ToolGatewayConfiguration {

    @Bean
    @Primary
    RecordingMcpToolGateway testMcpToolGateway(ObjectMapper objectMapper) {
      return new RecordingMcpToolGateway(objectMapper);
    }
  }

  record ToolCall(AgentName agentName, McpToolName toolName) {}

  static final class RecordingMcpToolGateway implements McpToolGateway {

    private final ObjectMapper objectMapper;
    private final List<ToolCall> calls = new CopyOnWriteArrayList<>();

    RecordingMcpToolGateway(ObjectMapper objectMapper) {
      this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> discover(AgentName agentName) {
      return McpToolName.names();
    }

    @Override
    public McpToolResult call(
        AgentName agentName, McpToolName toolName, Map<String, Object> arguments) {
      calls.add(new ToolCall(agentName, toolName));
      var data = objectMapper.createObjectNode();
      data.put("tool", toolName.value());
      return new McpToolResult(
          McpToolResult.Status.SUCCEEDED,
          McpToolResult.ProviderMode.MOCK,
          data,
          List.of("Mock MCP provider is active."));
    }

    List<ToolCall> calls() {
      return List.copyOf(calls);
    }

    void clear() {
      calls.clear();
    }
  }
}
