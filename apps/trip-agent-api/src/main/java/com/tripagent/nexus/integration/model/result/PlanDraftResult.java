package com.tripagent.nexus.integration.model.result;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record PlanDraftResult(
    @NotBlank String title,
    @NotBlank String summary,
    @NotEmpty List<@Valid DraftDay> days,
    @NotNull @Valid DraftBudget budget,
    @NotNull List<@NotBlank String> assumptions) {

  public record DraftDay(
      @NotNull LocalDate date, @NotBlank String city, @NotNull List<@Valid DraftItem> items) {}

  public record DraftItem(
      @NotBlank String title,
      @NotBlank String category,
      @NotNull OffsetDateTime startTime,
      @NotNull OffsetDateTime endTime,
      @NotBlank String location,
      @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal estimatedCost,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @NotNull List<@NotBlank String> evidenceIds) {}

  public record DraftBudget(
      @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal estimatedTotal,
      @NotNull @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal budgetLimit,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}
}
