package com.tripagent.nexus.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PlanComparison(
    UUID planFamilyId,
    UUID fromPlanId,
    UUID toPlanId,
    int fromVersion,
    int toVersion,
    BigDecimal budgetDelta,
    List<UUID> addedItemIds,
    List<UUID> removedItemIds,
    List<UUID> changedItemIds,
    List<String> resolvedConflictCodes,
    List<String> introducedConflictCodes,
    String summary) {

  public PlanComparison {
    addedItemIds = List.copyOf(addedItemIds);
    removedItemIds = List.copyOf(removedItemIds);
    changedItemIds = List.copyOf(changedItemIds);
    resolvedConflictCodes = List.copyOf(resolvedConflictCodes);
    introducedConflictCodes = List.copyOf(introducedConflictCodes);
  }
}
