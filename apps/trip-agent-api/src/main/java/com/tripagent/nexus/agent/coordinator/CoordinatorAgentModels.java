package com.tripagent.nexus.agent.coordinator;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.tripagent.nexus.agent.AgentContracts.AgentInput;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.AgentStatus;
import com.tripagent.nexus.agent.ContextEnvelope;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class CoordinatorAgentModels {

  private CoordinatorAgentModels() {}

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record Input(
      @NotNull UUID taskId,
      @NotNull @Valid ContextEnvelope context,
      @NotBlank @Size(max = 5000) String requestText,
      @JsonProperty(required = false) UUID executionToken)
      implements AgentInput {
    public Input(UUID taskId, ContextEnvelope context, String requestText) {
      this(taskId, context, requestText, null);
    }
  }

  public record Output(
      @NotNull CompletionStatus completionStatus,
      @NotNull List<@Valid DelegatedTask> delegatedTasks,
      @NotNull @Size(max = 5) List<@Valid PlanAlternative> alternatives,
      @NotNull JsonNode plan) {}

  public record PlanAlternative(
      @NotBlank String candidateKey,
      @NotNull PlanStrategy strategy,
      @NotBlank String title,
      @NotBlank @Size(max = 2000) String summary) {}

  public record DelegatedTask(
      @NotNull UUID taskId, @NotNull AgentName agentName, @NotNull AgentStatus status) {}

  public enum CompletionStatus {
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED
  }
}
