package com.tripagent.nexus.agent.transport;

import com.tripagent.nexus.agent.AgentContracts.AgentInput;
import com.tripagent.nexus.agent.ContextEnvelope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class TransportAgentModels {

  private TransportAgentModels() {}

  public record Input(
      @NotNull UUID taskId,
      @NotNull @Valid ContextEnvelope context,
      @NotBlank String origin,
      @NotEmpty List<@NotBlank String> destinations)
      implements AgentInput {}

  public record Output(@NotNull List<@Valid TransportOption> options) {}

  public record TransportOption(
      @NotBlank String optionId,
      @NotBlank String mode,
      @NotBlank String origin,
      @NotBlank String destination,
      @NotNull OffsetDateTime departureTime,
      @NotNull OffsetDateTime arrivalTime,
      @NotNull @Digits(integer = 15, fraction = 4) BigDecimal cost,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}
}
