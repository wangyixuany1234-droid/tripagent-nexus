package com.tripagent.nexus.infrastructure.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStatus;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStrategy;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TravelPlanConsistencyTest {

  private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

  @Test
  void completionWinsWhenPlanWasPersistedBeforeCancellation() {
    TravelPlanEntity plan = plan();
    plan.markReady("Completed", NOW);

    assertThatThrownBy(() -> plan.cancel(NOW.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PLAN_ALREADY_TERMINAL");
    plan.markFailed("Late failure", NOW.plusSeconds(2));
    assertThat(plan.getStatus()).isEqualTo(PlanStatus.READY);
  }

  @Test
  void cancellationWinsWhenItWasPersistedBeforeCompletion() {
    TravelPlanEntity plan = plan();
    plan.cancel(NOW);

    assertThatThrownBy(
            () ->
                plan.complete(
                    "Late completion",
                    JsonNodeFactory.instance.arrayNode(),
                    JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.arrayNode(),
                    JsonNodeFactory.instance.arrayNode(),
                    PlanStatus.READY,
                    "Late result",
                    NOW.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PLAN_ALREADY_TERMINAL");
    plan.markFailed("Late failure", NOW.plusSeconds(2));
    assertThat(plan.getStatus()).isEqualTo(PlanStatus.CANCELLED);
  }

  private static TravelPlanEntity plan() {
    UUID planId = UUID.randomUUID();
    return new TravelPlanEntity(
        planId,
        UUID.randomUUID(),
        planId,
        null,
        "stage6-plan",
        PlanStrategy.BALANCED,
        1,
        "Synthetic plan",
        "",
        JsonNodeFactory.instance.arrayNode(),
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.arrayNode(),
        JsonNodeFactory.instance.arrayNode(),
        "STAGE6_TEST",
        JsonNodeFactory.instance.objectNode(),
        "",
        false,
        NOW);
  }
}
