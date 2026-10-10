package com.tripagent.nexus.application.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.tripagent.nexus.agent.AgentContracts.AgentEvidence;
import com.tripagent.nexus.agent.AgentContracts.ConstraintKind;
import com.tripagent.nexus.agent.ContextEnvelope;
import com.tripagent.nexus.domain.model.DomainEnums.Pace;
import com.tripagent.nexus.domain.model.DomainEnums.TravelerType;
import com.tripagent.nexus.domain.model.Place;
import com.tripagent.nexus.domain.model.ReplanInputSnapshot;
import com.tripagent.nexus.domain.model.TravelRequirement;
import com.tripagent.nexus.domain.model.Traveler;
import com.tripagent.nexus.infrastructure.config.PlanningProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanningContextFactoryTest {

  private final PlanningContextFactory factory =
      new PlanningContextFactory(new PlanningProperties());

  @Test
  void inferredMemoryRemainsEvidenceUntilTheUserExplicitlyAddsThePreference() {
    AgentEvidence inferredPreference =
        new AgentEvidence(
            "memory-local-food",
            "USER_MEMORY",
            "Inferred dining preference",
            "The user may prefer local food.",
            new BigDecimal("0.8500"),
            false);
    PlanningKnowledgeService.KnowledgeResult knowledge =
        new PlanningKnowledgeService.KnowledgeResult(List.of(inferredPreference), false, List.of());

    ContextEnvelope inferredOnly =
        factory.create(
            requirement(List.of()), List.of(), knowledge, 1, ReplanInputSnapshot.empty());

    assertThat(inferredOnly.retrievedEvidence()).containsExactly(inferredPreference);
    assertThat(inferredOnly.userConstraints())
        .noneMatch(constraint -> constraint.statement().contains("local food"));
    assertThat(inferredOnly.userConstraints())
        .filteredOn(constraint -> constraint.kind() == ConstraintKind.HARD)
        .extracting(constraint -> constraint.constraintId())
        .containsExactlyInAnyOrder("dates", "budget", "travelers");

    ContextEnvelope explicitlyAdded =
        factory.create(
            requirement(List.of("museum")),
            List.of(),
            knowledge,
            2,
            new ReplanInputSnapshot(List.of(), List.of(), null, null, List.of("local food"), ""));

    assertThat(explicitlyAdded.userConstraints())
        .filteredOn(constraint -> constraint.constraintId().equals("interests"))
        .singleElement()
        .satisfies(
            constraint -> {
              assertThat(constraint.kind()).isEqualTo(ConstraintKind.SOFT);
              assertThat(constraint.statement()).contains("museum", "local food");
              assertThat(constraint.protectedFromSummarization()).isFalse();
            });
  }

  private static TravelRequirement requirement(List<String> interests) {
    return new TravelRequirement(
        UUID.randomUUID(),
        new Place(null, "Shanghai", null, null, null),
        List.of(new Place(null, "Beijing", null, null, null)),
        LocalDate.parse("2026-10-01"),
        LocalDate.parse("2026-10-03"),
        List.of(
            new Traveler(UUID.randomUUID(), "Synthetic traveler", TravelerType.ADULT, List.of())),
        new BigDecimal("5000.0000"),
        "CNY",
        interests,
        Pace.BALANCED,
        List.of(),
        List.of(),
        "",
        Instant.parse("2026-09-01T00:00:00Z"));
  }
}
