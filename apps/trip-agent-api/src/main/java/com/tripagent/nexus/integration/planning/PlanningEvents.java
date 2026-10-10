package com.tripagent.nexus.integration.planning;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PlanningEvents {

  private PlanningEvents() {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PlanRequestedPayload(
      UUID planId,
      TaskType taskType,
      String requestKind,
      int requirementVersion,
      int contextVersion,
      int candidateTarget,
      List<String> strategyPreferences,
      UUID baseItineraryVersionId,
      Instant requestedAt) {

    public PlanRequestedPayload {
      strategyPreferences = List.copyOf(strategyPreferences);
    }
  }
}
