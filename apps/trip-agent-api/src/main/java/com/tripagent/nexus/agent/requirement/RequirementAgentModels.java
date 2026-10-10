package com.tripagent.nexus.agent.requirement;

import com.tripagent.nexus.agent.AgentContracts.AgentInput;
import com.tripagent.nexus.agent.ContextEnvelope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class RequirementAgentModels {

  private RequirementAgentModels() {}

  public record Input(
      @NotNull UUID taskId,
      @NotNull @Valid ContextEnvelope context,
      @NotBlank @Size(max = 5000) String requestText)
      implements AgentInput {}

  public record Output(
      @NotBlank String origin,
      @NotEmpty List<@NotBlank String> destinations,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate,
      @NotNull @Digits(integer = 15, fraction = 4) BigDecimal budgetLimit,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @NotNull List<@NotBlank String> hardConstraints,
      @NotNull List<@NotBlank String> softConstraints,
      @NotNull List<@NotBlank String> ambiguities) {}
}
