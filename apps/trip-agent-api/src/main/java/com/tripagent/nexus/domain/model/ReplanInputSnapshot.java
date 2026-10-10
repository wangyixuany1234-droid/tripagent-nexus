package com.tripagent.nexus.domain.model;

import com.tripagent.nexus.domain.model.DomainEnums.Pace;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ReplanInputSnapshot(
    List<UUID> lockedItemIds,
    List<UUID> removedItemIds,
    BigDecimal adjustedTotalBudget,
    Pace adjustedPace,
    List<String> addedPreferences,
    String naturalLanguageRequest) {

  public ReplanInputSnapshot {
    lockedItemIds = lockedItemIds == null ? List.of() : List.copyOf(lockedItemIds);
    removedItemIds = removedItemIds == null ? List.of() : List.copyOf(removedItemIds);
    addedPreferences = addedPreferences == null ? List.of() : List.copyOf(addedPreferences);
    naturalLanguageRequest = naturalLanguageRequest == null ? "" : naturalLanguageRequest;
  }

  public static ReplanInputSnapshot empty() {
    return new ReplanInputSnapshot(List.of(), List.of(), null, null, List.of(), "");
  }
}
