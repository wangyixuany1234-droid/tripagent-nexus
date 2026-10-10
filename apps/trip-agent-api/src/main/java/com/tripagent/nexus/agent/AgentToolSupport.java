package com.tripagent.nexus.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AgentContracts.AgentWarning;
import com.tripagent.nexus.agent.AgentContracts.ToolResultContext;
import com.tripagent.nexus.guardrail.PromptInjectionGuard;
import com.tripagent.nexus.skill.McpToolGateway;
import com.tripagent.nexus.skill.McpToolName;
import com.tripagent.nexus.skill.McpToolResult;
import java.util.List;
import java.util.Map;

public final class AgentToolSupport {

  private AgentToolSupport() {}

  public static McpToolResult call(
      McpToolGateway gateway,
      AgentName agentName,
      McpToolName toolName,
      Map<String, Object> arguments,
      ObjectMapper objectMapper) {
    try {
      McpToolResult result = gateway.call(agentName, toolName, arguments);
      if (result == null
          || result.status() == null
          || result.providerMode() == null
          || result.data() == null
          || !result.data().isObject()) {
        throw new IllegalArgumentException("MCP_RESULT_INVALID");
      }
      if (result.status() == McpToolResult.Status.SUCCEEDED) {
        String untrustedText =
            result.data().toString() + "\n" + String.join("\n", result.warnings());
        if (!PromptInjectionGuard.detect(untrustedText).allowed()) {
          return unavailable(
              result,
              "MCP_RESULT_UNTRUSTED",
              "The tool result was rejected as untrusted content.",
              objectMapper);
        }
        if (toolName == McpToolName.ROUTE_CALCULATE
            && result.data().has("reachable")
            && (!result.data().path("reachable").isBoolean()
                || !result.data().path("reachable").asBoolean())) {
          return unavailable(
              result, "MCP_ROUTE_UNREACHABLE", "No reachable route is available.", objectMapper);
        }
        if (toolName == McpToolName.EXCHANGE_RATE
            && result.providerMode() == McpToolResult.ProviderMode.LIVE
            && !java.util.Objects.equals(
                arguments.get("fromCurrency"), arguments.get("toCurrency"))) {
          String requested = String.valueOf(arguments.get("asOf"));
          String supplied = result.data().path("asOf").asText();
          if (supplied.isBlank()
              || !java.time.LocalDate.parse(supplied)
                  .equals(java.time.LocalDate.parse(requested))) {
            return unavailable(
                result,
                "MCP_RATE_STALE",
                "A rate for the requested date is unavailable.",
                objectMapper);
          }
        }
      }
      return result;
    } catch (RuntimeException exception) {
      var data = objectMapper.createObjectNode();
      data.put("code", "MCP_TOOL_UNAVAILABLE");
      return new McpToolResult(
          McpToolResult.Status.UNAVAILABLE,
          McpToolResult.ProviderMode.LIVE,
          data,
          List.of("The MCP tool call did not complete."));
    }
  }

  private static McpToolResult unavailable(
      McpToolResult original, String code, String message, ObjectMapper mapper) {
    return new McpToolResult(
        McpToolResult.Status.UNAVAILABLE,
        original.providerMode(),
        mapper.createObjectNode().put("code", code),
        List.of(message));
  }

  public static ToolResultContext context(
      McpToolName toolName, McpToolResult result, ObjectMapper objectMapper) {
    return new ToolResultContext(
        toolName.value(), result.status().name(), objectMapper.valueToTree(result));
  }

  public static List<AgentWarning> warnings(McpToolName toolName, McpToolResult result) {
    if (result.status() == McpToolResult.Status.SUCCEEDED) {
      return result.warnings().stream()
          .map(
              message ->
                  new AgentWarning(
                      result.providerMode() == McpToolResult.ProviderMode.MOCK
                          ? "MCP_MOCK_DATA"
                          : "MCP_TOOL_WARNING",
                      message))
          .toList();
    }
    return List.of(
        new AgentWarning(
            "MCP_TOOL_" + result.status().name(),
            toolName.value() + " did not return a live result."));
  }

  public static AgentStatus status(McpToolResult result) {
    return result.status() == McpToolResult.Status.SUCCEEDED
        ? AgentStatus.SUCCEEDED
        : AgentStatus.DEGRADED;
  }
}
