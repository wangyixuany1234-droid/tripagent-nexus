package com.tripagent.nexus.agent;

import com.tripagent.nexus.agent.AgentContracts.AgentInput;
import com.tripagent.nexus.agent.AgentContracts.AgentPolicy;
import com.tripagent.nexus.skill.McpToolName;
import java.util.Set;
import org.a2aproject.sdk.spec.AgentCard;

public record AgentDefinition<I extends AgentInput, O>(
    AgentName name,
    AgentCard card,
    Class<I> inputType,
    Class<O> outputType,
    Set<McpToolName> skillAllowlist,
    AgentPolicy policy) {

  public AgentDefinition {
    skillAllowlist = Set.copyOf(skillAllowlist);
  }
}
