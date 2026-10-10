package com.tripagent.nexus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripagent.nexus.agent.AgentContracts.BookingContext;
import com.tripagent.nexus.agent.AgentContracts.ConstraintKind;
import com.tripagent.nexus.agent.AgentContracts.UserConstraint;
import com.tripagent.nexus.agent.coordinator.CoordinatorAgentModels;
import com.tripagent.nexus.agent.requirement.RequirementAgentModels;
import com.tripagent.nexus.agent.research.ResearchAgentModels;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentSchemaValidatorTest {

  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final AgentSchemaValidator validator =
      new AgentSchemaValidator(
          objectMapper, Validation.buildDefaultValidatorFactory().getValidator());

  @Test
  void validatesConcreteAgentOutputSchema() {
    var output =
        new RequirementAgentModels.Output(
            "Shanghai",
            List.of("Beijing"),
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 3),
            new BigDecimal("3000.0000"),
            "CNY",
            List.of("Fixed dates"),
            List.of("Culture"),
            List.of());

    assertThatCode(
            () ->
                validator.validate(
                    output, RequirementAgentModels.Output.class, "REQUIREMENT_OUTPUT_INVALID"))
        .doesNotThrowAnyException();
  }

  @Test
  void validatesResearchInputContainingPriorAgentResult() {
    var requirement =
        new RequirementAgentModels.Output(
            "Shanghai",
            List.of("Beijing"),
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 3),
            new BigDecimal("3000.0000"),
            "CNY",
            List.of("Fixed dates"),
            List.of("Culture"),
            List.of());
    ContextEnvelope context =
        new ContextEnvelope(
                List.of("All monetary totals are computed by Java."),
                List.of(
                    new UserConstraint(
                        "budget", ConstraintKind.HARD, "Total budget is CNY 3000.", true)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1,
                8000,
                List.of())
            .withAgentResult(
                new AgentContracts.AgentResultContext(
                    java.util.UUID.randomUUID(),
                    AgentName.REQUIREMENT,
                    AgentStatus.SUCCEEDED,
                    objectMapper.valueToTree(requirement)));
    var input =
        new ResearchAgentModels.Input(
            java.util.UUID.randomUUID(), context, List.of("Beijing"), List.of("Areas"));

    assertThatCode(
            () ->
                validator.parseAndValidate(
                    objectMapper.convertValue(input, Object.class),
                    ResearchAgentModels.Input.class,
                    "RESEARCH_INPUT_INVALID"))
        .doesNotThrowAnyException();
  }

  @Test
  void validatesCoordinatorInputContainingUnconfirmedBookingWithMissingOptionalFields() {
    ContextEnvelope context =
        new ContextEnvelope(
            List.of("Do not treat unconfirmed booking fields as facts."),
            List.of(),
            List.of(
                new BookingContext(
                    UUID.randomUUID(),
                    "HOTEL",
                    null,
                    null,
                    null,
                    null,
                    false,
                    "Hotel booking awaiting review")),
            List.of(),
            List.of(),
            List.of(),
            1,
            8000,
            List.of());
    var input =
        new CoordinatorAgentModels.Input(
            UUID.randomUUID(), context, "Plan a synthetic Beijing trip.");

    assertThatCode(
            () ->
                validator.parseAndValidate(
                    objectMapper.convertValue(input, Object.class),
                    CoordinatorAgentModels.Input.class,
                    "COORDINATOR_INPUT_INVALID"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsUnknownAgentInputFields() {
    var input =
        new ResearchAgentModels.Input(
            java.util.UUID.randomUUID(),
            ContextEnvelope.empty(1, 8000),
            List.of("Beijing"),
            List.of("Areas"));
    ObjectNode payload = objectMapper.valueToTree(input);
    payload.put("rawUserProfile", "must not cross the A2A boundary");

    assertThatThrownBy(
            () ->
                validator.parseAndValidate(
                    payload, ResearchAgentModels.Input.class, "RESEARCH_INPUT_INVALID"))
        .isInstanceOf(AgentSchemaValidator.AgentValidationException.class)
        .satisfies(
            exception ->
                assertThat(((AgentSchemaValidator.AgentValidationException) exception).code())
                    .isEqualTo("RESEARCH_INPUT_INVALID"));
  }

  @Test
  void rejectsUnknownAgentOutputFields() {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("destinationSummary", "Evidence summary");
    payload.putArray("candidateAreas").add("Dongcheng");
    payload.put("evidenceInsufficient", false);
    payload.put("unvalidatedModelField", "not allowed");

    assertThatThrownBy(
            () ->
                validator.parseAndValidate(
                    payload, ResearchAgentModels.Output.class, "RESEARCH_OUTPUT_INVALID"))
        .isInstanceOf(AgentSchemaValidator.AgentValidationException.class)
        .satisfies(
            exception ->
                assertThat(((AgentSchemaValidator.AgentValidationException) exception).code())
                    .isEqualTo("RESEARCH_OUTPUT_INVALID"));
  }
}
