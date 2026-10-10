package com.tripagent.nexus.integration;

import static com.tripagent.nexus.testsupport.ChaosEvidence.duration;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.AgentStatus;
import com.tripagent.nexus.agent.AgentToolSupport;
import com.tripagent.nexus.infrastructure.config.McpProperties;
import com.tripagent.nexus.skill.McpToolCatalog;
import com.tripagent.nexus.skill.McpToolName;
import com.tripagent.nexus.skill.McpToolResult;
import com.tripagent.nexus.skill.OfficialMcpToolGateway;
import com.tripagent.nexus.testsupport.ChaosEvidence;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.agents.enabled=true", "app.mcp.provider-mode=MOCK"})
@ActiveProfiles({"test", "mock-model"})
@AutoConfigureEmbeddedDatabase(provider = EMBEDDED, type = POSTGRES)
@DirtiesContext
class Stage7McpTransportChaosTest {
  @LocalServerPort int port;
  @Autowired OfficialMcpToolGateway gateway;
  @Autowired McpProperties properties;
  @Autowired ObjectMapper mapper;
  @MockitoBean McpToolCatalog catalog;

  @ParameterizedTest
  @ValueSource(strings = {"mapTimeout", "weather", "staleRate", "route", "schema"})
  void officialMcpTransportPropagatesFailureAndRecovery(String fault) throws Exception {
    ChaosEvidence.run(
        "mcp." + fault,
        "OFFICIAL_MCP_HTTP_STUB_PROVIDER",
        "Official MCP client/server over loopback HTTP; provider catalog fault injected",
        record -> {
          properties.setBaseUrl(URI.create("http://127.0.0.1:" + port));
          properties.setRequestTimeout(duration("assertionTimeoutMs"));
          McpToolName tool =
              switch (fault) {
                case "weather" -> McpToolName.WEATHER_QUERY;
                case "staleRate" -> McpToolName.EXCHANGE_RATE;
                case "route" -> McpToolName.ROUTE_CALCULATE;
                default -> McpToolName.PLACE_SEARCH;
              };
          Map<String, Object> args =
              switch (tool) {
                case WEATHER_QUERY ->
                    Map.of("city", "Beijing", "startDate", "2026-10-01", "endDate", "2026-10-03");
                case EXCHANGE_RATE ->
                    Map.of(
                        "amount",
                        10,
                        "fromCurrency",
                        "CNY",
                        "toCurrency",
                        "USD",
                        "asOf",
                        "2026-10-01");
                case ROUTE_CALCULATE ->
                    Map.of(
                        "origin",
                        "A",
                        "destination",
                        "B",
                        "departureTime",
                        "2026-10-01T10:00:00Z",
                        "modes",
                        List.of("WALK"));
                default -> Map.of("query", "culture", "city", "Beijing");
              };
          var valid =
              new McpToolResult(
                  McpToolResult.Status.SUCCEEDED,
                  McpToolResult.ProviderMode.LIVE,
                  mapper.createObjectNode().put("asOf", "2026-10-01").put("reachable", true),
                  List.of());
          when(catalog.execute(eq(tool), any(), any())).thenReturn(valid);
          assertThat(gateway.call(AgentName.TRANSPORT, tool, args).status())
              .isEqualTo(McpToolResult.Status.SUCCEEDED);
          clearInvocations(catalog);
          if (fault.equals("mapTimeout")) properties.setRequestTimeout(duration("callTimeoutMs"));
          when(catalog.execute(eq(tool), any(), any()))
              .thenAnswer(
                  ignored -> {
                    if (fault.equals("mapTimeout"))
                      Thread.sleep(duration("callTimeoutMs").multipliedBy(3));
                    return switch (fault) {
                      case "weather" ->
                          new McpToolResult(
                              McpToolResult.Status.FAILED,
                              McpToolResult.ProviderMode.LIVE,
                              mapper.createObjectNode(),
                              List.of("Unavailable forecast."));
                      case "staleRate" ->
                          new McpToolResult(
                              McpToolResult.Status.SUCCEEDED,
                              McpToolResult.ProviderMode.LIVE,
                              mapper.createObjectNode().put("asOf", "2020-01-01"),
                              List.of());
                      case "route" ->
                          new McpToolResult(
                              McpToolResult.Status.SUCCEEDED,
                              McpToolResult.ProviderMode.LIVE,
                              mapper.createObjectNode().put("reachable", false),
                              List.of());
                      case "schema" ->
                          new McpToolResult(
                              McpToolResult.Status.SUCCEEDED,
                              McpToolResult.ProviderMode.LIVE,
                              mapper.createArrayNode(),
                              List.of());
                      default -> valid;
                    };
                  });
          record.injected();
          long started = System.nanoTime();
          var result = AgentToolSupport.call(gateway, AgentName.TRANSPORT, tool, args, mapper);
          record.detected();
          assertThat(AgentToolSupport.status(result)).isEqualTo(AgentStatus.DEGRADED);
          assertThat(Duration.ofNanos(System.nanoTime() - started))
              .isLessThan(duration("assertionTimeoutMs"));
          verify(catalog, times(1)).execute(eq(tool), any(), any());
          record.degraded(
              "Unavailable, stale or unreachable tool data is excluded from successful results.");
          when(catalog.execute(eq(tool), any(), any())).thenReturn(valid);
          properties.setRequestTimeout(duration("assertionTimeoutMs"));
          record.cleared();
          assertThat(
                  AgentToolSupport.status(
                      AgentToolSupport.call(gateway, AgentName.TRANSPORT, tool, args, mapper)))
              .isEqualTo(AgentStatus.SUCCEEDED);
          record.recovered();
        });
  }
}
