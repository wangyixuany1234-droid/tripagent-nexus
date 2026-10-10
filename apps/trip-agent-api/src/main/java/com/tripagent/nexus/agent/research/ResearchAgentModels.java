package com.tripagent.nexus.agent.research;

import com.tripagent.nexus.agent.AgentContracts.AgentInput;
import com.tripagent.nexus.agent.ContextEnvelope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class ResearchAgentModels {

  private ResearchAgentModels() {}

  public record Input(
      @NotNull UUID taskId,
      @NotNull @Valid ContextEnvelope context,
      @NotEmpty List<@NotBlank String> destinations,
      @NotEmpty @Size(max = 20) List<@NotBlank String> researchQuestions)
      implements AgentInput {}

  public record Output(
      @NotBlank @Size(max = 5000) String destinationSummary,
      @NotNull List<@NotBlank String> candidateAreas,
      boolean evidenceInsufficient) {}
}
