package com.tripagent.nexus.agent.recommendation;

import com.tripagent.nexus.agent.AgentContracts.AgentInput;
import com.tripagent.nexus.agent.ContextEnvelope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class RecommendationAgentModels {

  private RecommendationAgentModels() {}

  public record Input(
      @NotNull UUID taskId,
      @NotNull @Valid ContextEnvelope context,
      @NotBlank String city,
      @NotNull List<@NotBlank String> candidateAreas)
      implements AgentInput {}

  public record Output(
      @NotNull List<@Valid Recommendation> recommendations, boolean evidenceInsufficient) {}

  public record Recommendation(
      @NotBlank String itemId,
      @NotNull RecommendationType type,
      @NotBlank String name,
      @NotBlank String area,
      @DecimalMin("-90.0") @DecimalMax("90.0") @Digits(integer = 3, fraction = 7) BigDecimal latitude,
      @DecimalMin("-180.0") @DecimalMax("180.0") @Digits(integer = 3, fraction = 7) BigDecimal longitude,
      @NotNull @Digits(integer = 15, fraction = 4) BigDecimal estimatedCost,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}

  public enum RecommendationType {
    ATTRACTION,
    RESTAURANT
  }
}
