package com.tripagent.nexus.skill;

import com.tripagent.nexus.agent.AgentName;
import java.util.Map;
import java.util.Set;

public interface McpToolGateway {

  Set<String> discover(AgentName agentName);

  McpToolResult call(AgentName agentName, McpToolName toolName, Map<String, Object> arguments);
}
