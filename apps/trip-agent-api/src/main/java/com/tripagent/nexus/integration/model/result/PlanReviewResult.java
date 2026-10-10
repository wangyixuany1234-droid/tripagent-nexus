package com.tripagent.nexus.integration.model.result;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public record PlanReviewResult(
    boolean valid,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal score,
    @NotNull List<@Valid ReviewConflict> conflicts,
    @NotNull List<@NotBlank String> repairSuggestions) {

  public record ReviewConflict(
      @NotBlank String code,
      @NotBlank String severity,
      @NotBlank String message,
      @NotBlank String affectedItemId) {}
}
