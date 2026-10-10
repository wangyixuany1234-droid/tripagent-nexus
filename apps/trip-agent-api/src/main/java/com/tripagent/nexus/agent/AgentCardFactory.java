package com.tripagent.nexus.agent;

import com.tripagent.nexus.skill.McpToolName;
import java.util.List;
import java.util.Set;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;

public final class AgentCardFactory {

  private static final AgentProvider PROVIDER =
      new AgentProvider("TripAgent Nexus", "https://tripagent.example");

  private AgentCardFactory() {}

  public static AgentCard create(
      AgentName name, String description, Set<McpToolName> tools, String version) {
    List<AgentSkill> skills =
        tools.stream()
            .map(
                tool ->
                    AgentSkill.builder()
                        .id(tool.value())
                        .name(tool.value())
                        .description("Invoke the " + tool.value() + " MCP tool.")
                        .tags(List.of("travel", "mcp"))
                        .inputModes(List.of("application/json"))
                        .outputModes(List.of("application/json"))
                        .build())
            .toList();
    AgentInterface agentInterface =
        new AgentInterface(
            "JSONRPC",
            "https://agents.internal.tripagent.example/a2a/"
                + name.cardName().replace("Agent", "").toLowerCase());
    return AgentCard.builder()
        .name(name.cardName())
        .description(description)
        .provider(PROVIDER)
        .version(version)
        .capabilities(
            AgentCapabilities.builder()
                .streaming(false)
                .pushNotifications(false)
                .extendedAgentCard(false)
                .build())
        .defaultInputModes(List.of("application/json"))
        .defaultOutputModes(List.of("application/json"))
        .skills(skills)
        .supportedInterfaces(List.of(agentInterface))
        .build();
  }
}
