package com.tripagent.nexus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.skill.McpToolGateway;
import com.tripagent.nexus.skill.McpToolName;
import com.tripagent.nexus.skill.McpToolResult;
import com.tripagent.nexus.testsupport.ChaosEvidence;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpChaosTest {
  @ParameterizedTest
  @ValueSource(strings = {"mapTimeout", "weather", "staleRate", "route", "schema"})
  void invalidOrUnavailableToolResultIsNeverSuccessful(String fault) throws Exception {
    ChaosEvidence.run(
        "mcp." + fault,
        "GATEWAY_STUB",
        "AgentToolSupport consuming MCP gateway results; no external tool or MCP transport",
        record -> {
          var mapper = new ObjectMapper();
          var failed = new AtomicBoolean(true);
          McpToolName tool =
              switch (fault) {
                case "weather" -> McpToolName.WEATHER_QUERY;
                case "staleRate" -> McpToolName.EXCHANGE_RATE;
                case "route" -> McpToolName.ROUTE_CALCULATE;
                default -> McpToolName.PLACE_SEARCH;
              };
          McpToolGateway gateway =
              new McpToolGateway() {
                @Override
                public java.util.Set<String> discover(AgentName agent) {
                  return java.util.Set.of(tool.value());
                }

                @Override
                public McpToolResult call(
                    AgentName agent, McpToolName name, Map<String, Object> arguments) {
                  var data =
                      mapper.createObjectNode().put("asOf", "2026-10-01").put("reachable", true);
                  if (failed.get()) {
                    switch (fault) {
                      case "mapTimeout" -> throw new IllegalStateException("synthetic timeout");
                      case "weather" -> {
                        return new McpToolResult(
                            McpToolResult.Status.FAILED,
                            McpToolResult.ProviderMode.LIVE,
                            data,
                            List.of());
                      }
                      case "staleRate" -> data.put("asOf", "2020-01-01");
                      case "route" -> data.put("reachable", false);
                      case "schema" -> {
                        return new McpToolResult(
                            null,
                            McpToolResult.ProviderMode.LIVE,
                            mapper.createArrayNode(),
                            List.of());
                      }
                      default -> throw new IllegalArgumentException(fault);
                    }
                  }
                  return new McpToolResult(
                      McpToolResult.Status.SUCCEEDED,
                      McpToolResult.ProviderMode.LIVE,
                      data,
                      List.of());
                }
              };
          Map<String, Object> args =
              Map.of("asOf", "2026-10-01", "fromCurrency", "CNY", "toCurrency", "USD");
          record.injected();
          var result = AgentToolSupport.call(gateway, AgentName.RESEARCH, tool, args, mapper);
          record.detected();
          assertThat(result.status())
              .isIn(McpToolResult.Status.FAILED, McpToolResult.Status.UNAVAILABLE);
          assertThat(AgentToolSupport.status(result)).isEqualTo(AgentStatus.DEGRADED);
          assertThat(AgentToolSupport.warnings(tool, result)).isNotEmpty();
          record.degraded("The unavailable tool result is excluded; agent is explicitly degraded.");
          failed.set(false);
          record.cleared();
          assertThat(
                  AgentToolSupport.status(
                      AgentToolSupport.call(gateway, AgentName.RESEARCH, tool, args, mapper)))
              .isEqualTo(AgentStatus.SUCCEEDED);
          record.recovered();
        });
  }
}
