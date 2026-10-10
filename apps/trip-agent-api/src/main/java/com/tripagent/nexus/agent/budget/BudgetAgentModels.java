package com.tripagent.nexus.agent.budget;

import com.tripagent.nexus.agent.AgentContracts.AgentInput;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.ContextEnvelope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class BudgetAgentModels {

  private BudgetAgentModels() {}

  public record Input(
      @NotNull UUID taskId,
      @NotNull @Valid ContextEnvelope context,
      @NotNull @Digits(integer = 15, fraction = 4) BigDecimal budgetLimit,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @NotNull List<@Valid CostItem> costItems)
      implements AgentInput {}

  public record CostItem(
      @NotNull AgentName sourceAgent,
      @NotBlank String category,
      @NotBlank String description,
      @NotNull @Digits(integer = 15, fraction = 4) BigDecimal amount,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}

  public record Output(
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @NotNull @Digits(integer = 15, fraction = 4) BigDecimal total,
      @NotNull @Digits(integer = 15, fraction = 4) BigDecimal budgetLimit,
      @NotNull @Digits(integer = 15, fraction = 4) BigDecimal overrun,
      @NotNull List<@Valid CostItem> lineItems) {}
}
