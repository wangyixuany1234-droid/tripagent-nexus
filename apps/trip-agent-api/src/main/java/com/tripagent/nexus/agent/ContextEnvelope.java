package com.tripagent.nexus.agent;

import com.tripagent.nexus.agent.AgentContracts.AgentEvidence;
import com.tripagent.nexus.agent.AgentContracts.AgentResultContext;
import com.tripagent.nexus.agent.AgentContracts.BookingContext;
import com.tripagent.nexus.agent.AgentContracts.PrivacyLabel;
import com.tripagent.nexus.agent.AgentContracts.ToolResultContext;
import com.tripagent.nexus.agent.AgentContracts.UserConstraint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

public record ContextEnvelope(
    @NotNull @Size(max = 50) List<String> systemRules,
    @NotNull @Size(max = 100) List<@Valid UserConstraint> userConstraints,
    @NotNull @Size(max = 100) List<@Valid BookingContext> parsedBookings,
    @NotNull @Size(max = 200) List<@Valid AgentEvidence> retrievedEvidence,
    @NotNull @Size(max = 100) List<@Valid ToolResultContext> toolResults,
    @NotNull @Size(max = 100) List<@Valid AgentResultContext> agentResults,
    @Min(1) int planVersion,
    @Min(256) @Max(200000) int tokenBudget,
    @NotNull @Size(max = 100) List<@Valid PrivacyLabel> privacyLabels) {

  public ContextEnvelope {
    systemRules = List.copyOf(systemRules);
    userConstraints = List.copyOf(userConstraints);
    parsedBookings = List.copyOf(parsedBookings);
    retrievedEvidence = List.copyOf(retrievedEvidence);
    toolResults = List.copyOf(toolResults);
    agentResults = List.copyOf(agentResults);
    privacyLabels = List.copyOf(privacyLabels);
  }

  public static ContextEnvelope empty(int planVersion, int tokenBudget) {
    return new ContextEnvelope(
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        planVersion,
        tokenBudget,
        List.of());
  }

  public ContextEnvelope withToolResult(ToolResultContext toolResult) {
    List<ToolResultContext> updated = new ArrayList<>(toolResults);
    updated.add(toolResult);
    return new ContextEnvelope(
        systemRules,
        userConstraints,
        parsedBookings,
        retrievedEvidence,
        updated,
        agentResults,
        planVersion,
        tokenBudget,
        privacyLabels);
  }

  public ContextEnvelope withAgentResult(AgentResultContext agentResult) {
    List<AgentResultContext> updated = new ArrayList<>(agentResults);
    updated.add(agentResult);
    return new ContextEnvelope(
        systemRules,
        userConstraints,
        parsedBookings,
        retrievedEvidence,
        toolResults,
        updated,
        planVersion,
        tokenBudget,
        privacyLabels);
  }
}
