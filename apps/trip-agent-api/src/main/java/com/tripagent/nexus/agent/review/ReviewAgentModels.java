package com.tripagent.nexus.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripagent.nexus.agent.AgentContracts.AgentInput;
import com.tripagent.nexus.agent.ContextEnvelope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;

public final class ReviewAgentModels {

  private ReviewAgentModels() {}

  public record Input(
      @NotNull UUID taskId,
      @NotNull @Valid ContextEnvelope context,
      @NotNull JsonNode candidatePlan)
      implements AgentInput {}

  public record Output(
      boolean valid, @Positive int reviewedPlanVersion, @NotNull List<@Valid ReviewIssue> issues) {}

  public record ReviewIssue(
      @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]+$") String code,
      @NotNull Severity severity,
      @NotBlank String message) {}

  public enum Severity {
    ERROR,
    WARNING,
    INFO
  }
}
