package com.tripagent.nexus.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BudgetSummary(
    BigDecimal totalBudget,
    BigDecimal estimatedTotal,
    BigDecimal remainingBudget,
    String currency,
    List<BudgetItem> items,
    List<DailyBudget> dailyBudgets) {

  public BudgetSummary {
    items = items == null ? List.of() : List.copyOf(items);
    dailyBudgets = dailyBudgets == null ? List.of() : List.copyOf(dailyBudgets);
  }

  public BudgetSummary(
      BigDecimal totalBudget,
      BigDecimal estimatedTotal,
      BigDecimal remainingBudget,
      String currency,
      List<BudgetItem> items) {
    this(totalBudget, estimatedTotal, remainingBudget, currency, items, List.of());
  }

  public record DailyBudget(LocalDate date, BigDecimal amount, String currency) {}
}
