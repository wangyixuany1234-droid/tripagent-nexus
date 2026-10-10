package com.tripagent.nexus.domain.model;

import java.math.BigDecimal;

public record BudgetItem(String category, String description, BigDecimal amount, String currency) {}
