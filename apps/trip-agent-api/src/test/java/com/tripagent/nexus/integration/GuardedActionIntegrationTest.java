package com.tripagent.nexus.integration;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.RefreshMode.BEFORE_EACH_TEST_METHOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.domain.model.DomainEnums.ActionAuditEvent;
import com.tripagent.nexus.domain.model.DomainEnums.GuardedActionStatus;
import com.tripagent.nexus.domain.model.DomainEnums.GuardedActionType;
import com.tripagent.nexus.guardrail.GuardedActionService;
import com.tripagent.nexus.infrastructure.persistence.entity.GuardedActionEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.GuardedActionAuditRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.GuardedActionRepository;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(
    provider = EMBEDDED,
    type = POSTGRES,
    refresh = BEFORE_EACH_TEST_METHOD)
class GuardedActionIntegrationTest {

  private static final String ACTOR = "user-guardrail-test";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private GuardedActionService actionService;
  @Autowired private GuardedActionRepository actionRepository;
  @Autowired private GuardedActionAuditRepository auditRepository;

  @Test
  void realCostActionRequiresExactExplicitConfirmationAndKeepsAuditTrail() throws Exception {
    UUID resourceId = UUID.randomUUID();
    JsonNode proposed =
        objectMapper.readTree(
            mockMvc
                .perform(
                    post("/api/v1/actions")
                        .header("Idempotency-Key", "guarded-action-create-0001")
                        .header("X-Actor-Id", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsBytes(
                                Map.of(
                                    "type",
                                    "PAYMENT",
                                    "resourceId",
                                    resourceId,
                                    "riskDescription",
                                    "Charges the selected booking provider.",
                                    "amount",
                                    new BigDecimal("123.4500"),
                                    "currency",
                                    "CNY",
                                    "expiresAt",
                                    "2030-01-01T00:00:00Z"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.auditTrail[0].eventType").value("PROPOSED"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray());
    UUID actionId = UUID.fromString(proposed.path("actionId").asText());
    long version = proposed.path("version").asLong();

    mockMvc
        .perform(
            post("/api/v1/actions/{actionId}/confirm", actionId)
                .header("Idempotency-Key", "guarded-action-confirm-bad-0001")
                .header("X-Actor-Id", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsBytes(
                        Map.of(
                            "actionId",
                            actionId,
                            "expectedVersion",
                            version,
                            "acknowledgedRisk",
                            false,
                            "acknowledgedAmount",
                            new BigDecimal("123.4500"),
                            "currency",
                            "CNY"))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ACTION_RISK_NOT_ACKNOWLEDGED"));

    mockMvc
        .perform(
            post("/api/v1/actions/{actionId}/confirm", actionId)
                .header("Idempotency-Key", "guarded-action-confirm-ok-0001")
                .header("X-Actor-Id", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsBytes(
                        Map.of(
                            "actionId",
                            actionId,
                            "expectedVersion",
                            version,
                            "acknowledgedRisk",
                            true,
                            "acknowledgedAmount",
                            new BigDecimal("123.4500"),
                            "currency",
                            "CNY"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.auditTrail.length()").value(2))
        .andExpect(jsonPath("$.auditTrail[1].eventType").value("CONFIRMED"));

    actionService.markExecuted(
        actionId, GuardedActionType.PAYMENT, resourceId, new BigDecimal("123.4500"), "CNY");
  }

  @Test
  void unconfirmedActionCannotCrossExecutionBoundary() throws Exception {
    UUID resourceId = UUID.randomUUID();
    JsonNode proposed =
        objectMapper.readTree(
            mockMvc
                .perform(
                    post("/api/v1/actions")
                        .header("Idempotency-Key", "guarded-action-create-0002")
                        .header("X-Actor-Id", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsBytes(
                                Map.of(
                                    "type",
                                    "ORDER_CREATE",
                                    "resourceId",
                                    resourceId,
                                    "riskDescription",
                                    "Creates a provider reservation.",
                                    "amount",
                                    new BigDecimal("0.0000"),
                                    "currency",
                                    "CNY",
                                    "expiresAt",
                                    Instant.parse("2030-01-01T00:00:00Z")))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsByteArray());

    assertThatThrownBy(
            () ->
                actionService.markExecuted(
                    UUID.fromString(proposed.path("actionId").asText()),
                    GuardedActionType.ORDER_CREATE,
                    resourceId,
                    BigDecimal.ZERO.setScale(4),
                    "CNY"))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception ->
                org.assertj.core.api.Assertions.assertThat(exception.getCode())
                    .isEqualTo("ACTION_EXECUTION_NOT_AUTHORIZED"));
  }

  @Test
  void expiredCandidatePersistsExpirationAndAuditWhenConfirmationIsRejected() throws Exception {
    Instant now = Instant.now();
    UUID actionId = UUID.randomUUID();
    GuardedActionEntity action =
        actionRepository.saveAndFlush(
            new GuardedActionEntity(
                actionId,
                GuardedActionType.PAYMENT,
                UUID.randomUUID(),
                "Charges the selected booking provider.",
                new BigDecimal("123.4500"),
                "CNY",
                now.minusSeconds(30),
                ACTOR,
                now.minusSeconds(120)));

    mockMvc
        .perform(
            post("/api/v1/actions/{actionId}/confirm", actionId)
                .header("Idempotency-Key", "guarded-action-confirm-expired-0001")
                .header("X-Actor-Id", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsBytes(
                        Map.of(
                            "actionId",
                            actionId,
                            "expectedVersion",
                            action.getEntityVersion(),
                            "acknowledgedRisk",
                            true,
                            "acknowledgedAmount",
                            new BigDecimal("123.4500"),
                            "currency",
                            "CNY"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACTION_NOT_PENDING"));

    assertThat(actionRepository.findById(actionId).orElseThrow().getStatus())
        .isEqualTo(GuardedActionStatus.EXPIRED);
    assertThat(auditRepository.findByActionIdOrderByOccurredAtAsc(actionId))
        .singleElement()
        .extracting(item -> item.getEventType())
        .isEqualTo(ActionAuditEvent.EXPIRED);
  }

  @Test
  void confirmedActionCannotExecuteAfterAuthorizationExpiry() {
    Instant now = Instant.now();
    UUID actionId = UUID.randomUUID();
    UUID resourceId = UUID.randomUUID();
    GuardedActionEntity action =
        new GuardedActionEntity(
            actionId,
            GuardedActionType.PAYMENT,
            resourceId,
            "Charges the selected booking provider.",
            new BigDecimal("123.4500"),
            "CNY",
            now.minusSeconds(30),
            ACTOR,
            now.minusSeconds(120));
    action.confirm(ACTOR, now.minusSeconds(60));
    actionRepository.saveAndFlush(action);

    assertThatThrownBy(
            () ->
                actionService.markExecuted(
                    actionId,
                    GuardedActionType.PAYMENT,
                    resourceId,
                    new BigDecimal("123.4500"),
                    "CNY"))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception ->
                assertThat(exception.getCode()).isEqualTo("ACTION_EXECUTION_NOT_AUTHORIZED"));

    assertThat(actionRepository.findById(actionId).orElseThrow().getStatus())
        .isEqualTo(GuardedActionStatus.EXPIRED);
    assertThat(auditRepository.findByActionIdOrderByOccurredAtAsc(actionId))
        .singleElement()
        .extracting(item -> item.getEventType())
        .isEqualTo(ActionAuditEvent.EXPIRED);
  }
}
