package com.tripagent.nexus.integration.model.result;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public record CandidateRecommendationResult(
    @NotEmpty List<@Valid Candidate> candidates, @NotBlank String recommendationSummary) {

  public record Candidate(
      @NotBlank String candidateId,
      @NotBlank String title,
      @NotBlank String reason,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal score,
      @NotNull List<@NotBlank String> evidenceIds,
      @NotNull List<@NotBlank String> tradeoffs) {}
}
