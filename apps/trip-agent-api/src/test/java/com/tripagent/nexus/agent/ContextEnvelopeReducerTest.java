package com.tripagent.nexus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AgentContracts.AgentEvidence;
import com.tripagent.nexus.agent.AgentContracts.AgentResultContext;
import com.tripagent.nexus.agent.AgentContracts.BookingContext;
import com.tripagent.nexus.agent.AgentContracts.ConstraintKind;
import com.tripagent.nexus.agent.AgentContracts.PrivacyClassification;
import com.tripagent.nexus.agent.AgentContracts.PrivacyLabel;
import com.tripagent.nexus.agent.AgentContracts.ToolResultContext;
import com.tripagent.nexus.agent.AgentContracts.UserConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextEnvelopeReducerTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final ContextEnvelopeReducer reducer = new ContextEnvelopeReducer(objectMapper);

  @Test
  void filtersContextByResponsibilityAndPrivacyLabels() {
    UUID bookingId = UUID.randomUUID();
    ContextEnvelope source =
        new ContextEnvelope(
            List.of("Keep deterministic totals."),
            List.of(
                new UserConstraint("dates", ConstraintKind.HARD, "Fixed travel dates.", true),
                new UserConstraint("diet", ConstraintKind.HARD, "No shellfish.", true)),
            List.of(
                new BookingContext(
                    bookingId,
                    "HOTEL",
                    Instant.parse("2026-10-01T02:00:00Z"),
                    Instant.parse("2026-10-03T04:00:00Z"),
                    new BigDecimal("1200.0000"),
                    "CNY",
                    true,
                    "Confirmed hotel")),
            List.of(evidence("source-1", new BigDecimal("0.9000"), "Nearby attraction")),
            List.of(
                tool("place_search", "place"),
                tool("budget_validate", "budget"),
                tool("booking_parse", "booking")),
            List.of(
                result(AgentName.REQUIREMENT, "requirement"),
                result(AgentName.TRANSPORT, "transport"),
                result(AgentName.RECOMMENDATION, "recommendation")),
            1,
            8000,
            List.of(
                new PrivacyLabel(
                    "/userConstraints/1",
                    PrivacyClassification.SENSITIVE,
                    Set.of(AgentName.BUDGET)),
                new PrivacyLabel(
                    "/parsedBookings",
                    PrivacyClassification.PERSONAL,
                    Set.of(AgentName.ACCOMMODATION))));

    ContextEnvelope research = reducer.select(AgentName.RESEARCH, source);
    ContextEnvelope accommodation = reducer.select(AgentName.ACCOMMODATION, source);
    ContextEnvelope budget = reducer.select(AgentName.BUDGET, source);

    assertThat(research.userConstraints())
        .extracting(UserConstraint::constraintId)
        .containsExactly("dates");
    assertThat(research.parsedBookings()).isEmpty();
    assertThat(research.toolResults())
        .extracting(ToolResultContext::toolName)
        .containsExactly("place_search");
    assertThat(research.agentResults())
        .extracting(AgentResultContext::agentName)
        .containsExactly(AgentName.REQUIREMENT);

    assertThat(accommodation.parsedBookings())
        .extracting(BookingContext::bookingId)
        .containsExactly(bookingId);
    assertThat(accommodation.agentResults())
        .extracting(AgentResultContext::agentName)
        .containsExactly(AgentName.REQUIREMENT, AgentName.TRANSPORT);

    assertThat(budget.userConstraints())
        .extracting(UserConstraint::constraintId)
        .containsExactly("dates", "diet");
    assertThat(budget.parsedBookings()).isEmpty();
    assertThat(budget.retrievedEvidence()).isEmpty();
    assertThat(budget.toolResults())
        .extracting(ToolResultContext::toolName)
        .containsExactly("budget_validate");
    assertThat(budget.agentResults())
        .extracting(AgentResultContext::agentName)
        .containsExactly(AgentName.TRANSPORT, AgentName.RECOMMENDATION);
  }

  @Test
  void deduplicatesBeforeTrimmingAndNeverDropsHardConstraintsOrConfirmedBookings() {
    UUID confirmedId = UUID.randomUUID();
    UUID tentativeId = UUID.randomUUID();
    String longText = "x".repeat(1600);
    UserConstraint hard = new UserConstraint("hard", ConstraintKind.HARD, longText, true);
    UserConstraint optional = new UserConstraint("optional", ConstraintKind.SOFT, longText, false);
    BookingContext confirmed =
        new BookingContext(
            confirmedId,
            "TRAIN",
            Instant.parse("2026-10-01T00:00:00Z"),
            Instant.parse("2026-10-01T02:00:00Z"),
            new BigDecimal("500.0000"),
            "CNY",
            true,
            longText);
    BookingContext tentative =
        new BookingContext(
            tentativeId,
            "HOTEL",
            Instant.parse("2026-10-01T02:00:00Z"),
            Instant.parse("2026-10-03T04:00:00Z"),
            new BigDecimal("1200.0000"),
            "CNY",
            false,
            longText);
    AgentEvidence evidence = evidence("source-1", new BigDecimal("0.1000"), longText);
    ContextEnvelope source =
        new ContextEnvelope(
            List.of("Preserve confirmed facts."),
            List.of(hard, hard, optional),
            List.of(confirmed, confirmed, tentative),
            List.of(evidence, evidence),
            List.of(tool("budget_validate", longText)),
            List.of(result(AgentName.BUDGET, longText)),
            1,
            256,
            List.of());

    ContextEnvelope reduced = reducer.select(AgentName.REVIEW, source);

    assertThat(reduced.userConstraints()).containsExactly(hard);
    assertThat(reduced.parsedBookings()).containsExactly(confirmed);
    assertThat(reduced.userConstraints())
        .extracting(UserConstraint::constraintId)
        .doesNotHaveDuplicates();
    assertThat(reduced.parsedBookings())
        .extracting(BookingContext::bookingId)
        .doesNotHaveDuplicates();
    assertThat(reduced.retrievedEvidence())
        .extracting(AgentEvidence::sourceId)
        .doesNotHaveDuplicates();
  }

  private AgentEvidence evidence(String sourceId, BigDecimal score, String excerpt) {
    return new AgentEvidence(sourceId, "GUIDE", "Guide", excerpt, score, false);
  }

  private ToolResultContext tool(String name, String value) {
    return new ToolResultContext(
        name, "SUCCEEDED", objectMapper.createObjectNode().put("value", value));
  }

  private AgentResultContext result(AgentName name, String value) {
    return new AgentResultContext(
        UUID.randomUUID(),
        name,
        AgentStatus.SUCCEEDED,
        objectMapper.createObjectNode().put("value", value));
  }
}
