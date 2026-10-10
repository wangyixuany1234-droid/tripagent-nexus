package com.tripagent.nexus.agent.accommodation;

import com.tripagent.nexus.agent.AgentContracts.AgentInput;
import com.tripagent.nexus.agent.ContextEnvelope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AccommodationAgentModels {

  private AccommodationAgentModels() {}

  public record Input(
      @NotNull UUID taskId,
      @NotNull @Valid ContextEnvelope context,
      @NotBlank String city,
      @NotNull OffsetDateTime checkIn,
      @NotNull OffsetDateTime checkOut)
      implements AgentInput {}

  public record Output(
      @NotNull OffsetDateTime arrivalAnchor, @NotNull List<@Valid StayOption> options) {}

  public record StayOption(
      @NotBlank String optionId,
      @NotBlank String name,
      @NotBlank String area,
      @NotNull @Digits(integer = 15, fraction = 4) BigDecimal cost,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}
}
