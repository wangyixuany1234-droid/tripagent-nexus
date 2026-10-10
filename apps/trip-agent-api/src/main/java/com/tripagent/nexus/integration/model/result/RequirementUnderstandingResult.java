package com.tripagent.nexus.integration.model.result;

import com.tripagent.nexus.domain.model.DomainEnums.Pace;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record RequirementUnderstandingResult(
    @NotBlank String origin,
    @NotEmpty List<@NotBlank String> destinations,
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate,
    @NotEmpty List<@Valid TravelerSummary> travelers,
    @NotNull @Digits(integer = 15, fraction = 4) BigDecimal totalBudget,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
    @NotEmpty List<@NotBlank String> interests,
    @NotNull Pace pace,
    @NotNull List<@NotBlank String> mustVisit,
    @NotNull List<@NotBlank String> avoid,
    @NotNull List<@NotBlank String> constraints,
    @NotNull List<@NotBlank String> assumptions) {

  public record TravelerSummary(
      @NotBlank String type,
      @Positive int count,
      @NotNull @Size(max = 20) List<@NotBlank String> accessibilityNeeds) {}
}
