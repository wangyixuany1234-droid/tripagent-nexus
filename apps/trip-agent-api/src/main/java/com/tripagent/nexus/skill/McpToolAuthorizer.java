package com.tripagent.nexus.skill;

import com.tripagent.nexus.agent.AgentName;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class McpToolAuthorizer {

  private final Map<AgentName, Set<McpToolName>> permissions = new EnumMap<>(AgentName.class);

  public McpToolAuthorizer() {
    permissions.put(AgentName.REQUIREMENT, Set.of(McpToolName.BOOKING_PARSE));
    permissions.put(
        AgentName.RESEARCH,
        Set.of(McpToolName.PLACE_SEARCH, McpToolName.WEATHER_QUERY, McpToolName.OPENING_HOURS));
    permissions.put(
        AgentName.TRANSPORT,
        Set.of(
            McpToolName.PLACE_SEARCH,
            McpToolName.WEATHER_QUERY,
            McpToolName.ROUTE_CALCULATE,
            McpToolName.EXCHANGE_RATE));
    permissions.put(
        AgentName.ACCOMMODATION,
        Set.of(
            McpToolName.PLACE_SEARCH,
            McpToolName.ROUTE_CALCULATE,
            McpToolName.OPENING_HOURS,
            McpToolName.EXCHANGE_RATE));
    permissions.put(
        AgentName.RECOMMENDATION,
        Set.of(
            McpToolName.PLACE_SEARCH,
            McpToolName.OPENING_HOURS,
            McpToolName.ROUTE_CALCULATE,
            McpToolName.WEATHER_QUERY));
    permissions.put(
        AgentName.BUDGET, Set.of(McpToolName.EXCHANGE_RATE, McpToolName.BUDGET_VALIDATE));
    permissions.put(
        AgentName.REVIEW,
        Set.of(
            McpToolName.ROUTE_CALCULATE, McpToolName.OPENING_HOURS, McpToolName.BUDGET_VALIDATE));
    permissions.put(AgentName.COORDINATOR, Set.of(McpToolName.ITINERARY_STORE));
  }

  public void requireAllowed(String agentCardName, McpToolName toolName) {
    AgentName agentName;
    try {
      agentName = AgentName.fromCardName(agentCardName);
    } catch (IllegalArgumentException exception) {
      throw new McpAuthorizationException("MCP_AGENT_IDENTITY_INVALID");
    }
    if (!permissions.getOrDefault(agentName, Set.of()).contains(toolName)) {
      throw new McpAuthorizationException("MCP_TOOL_NOT_ALLOWED");
    }
  }

  public static class McpAuthorizationException extends RuntimeException {
    private final String code;

    public McpAuthorizationException(String code) {
      super(code);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
