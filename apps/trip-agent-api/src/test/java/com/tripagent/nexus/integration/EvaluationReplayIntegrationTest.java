package com.tripagent.nexus.integration;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.RefreshMode.BEFORE_EACH_TEST_METHOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "app.agents.enabled=true",
      "app.mcp.provider-mode=MOCK",
      "app.mcp.service-token=test-mcp-service-token-000000000001",
    })
@AutoConfigureMockMvc
@ActiveProfiles({"test", "mock-model"})
@AutoConfigureEmbeddedDatabase(
    provider = EMBEDDED,
    type = POSTGRES,
    refresh = BEFORE_EACH_TEST_METHOD)
class EvaluationReplayIntegrationTest {

  private static final String OPERATIONS_TOKEN = "test-operations-token-000000000001";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void replaysVersionedDatasetAndPersistsMeasuredBaseline() throws Exception {
    JsonNode accepted =
        read(
            mockMvc
                .perform(
                    post("/api/v1/evaluations/runs")
                        .header("X-Operations-Token", OPERATIONS_TOKEN)
                        .header("Idempotency-Key", "phase-nine-evaluation-run-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetVersion\":\"phase-9-v1\"}"))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsByteArray());
    UUID runId = UUID.fromString(accepted.path("resourceId").asText());

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> assertThat(report(runId).path("status").asText()).isIn("SUCCEEDED", "FAILED"));

    JsonNode report = report(runId);
    writeBaseline(report);
    assertThat(report.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(report.path("sampleSize").asInt()).isEqualTo(12);
    assertThat(report.path("cases")).hasSize(12);
    assertThat(report.path("cases").findValuesAsText("passed")).containsOnly("true");
    assertThat(report.path("environment").path("mockMode").asBoolean()).isTrue();
    JsonNode metrics = report.path("metrics");
    assertThat(metrics.path("itineraryExecutabilityRate").decimalValue()).isEqualByComparingTo("1");
    assertThat(metrics.path("constraintConflictRate").decimalValue())
        .isEqualByComparingTo("0.4167");
    assertThat(metrics.path("evidenceCoverageRate").decimalValue()).isEqualByComparingTo("0.9524");
    assertThat(metrics.path("toolCallSuccessRate").decimalValue()).isEqualByComparingTo("0.6667");
    assertThat(metrics.path("structuredOutputValidityRate").decimalValue())
        .isEqualByComparingTo("0.6667");
    assertThat(metrics.path("replanSuccessRate").decimalValue()).isEqualByComparingTo("0.25");
    assertThat(metrics.path("endToEndLatencyMs").asLong()).isNotNegative();
    assertThat(metrics.path("tokenUsage").asLong()).isZero();

    Set<String> caseIds =
        StreamSupport.stream(report.path("cases").spliterator(), false)
            .map(item -> item.path("caseId").asText())
            .collect(Collectors.toSet());
    assertThat(caseIds)
        .containsExactlyInAnyOrder(
            "normal-beijing-three-day",
            "cross-city-travel",
            "insufficient-budget",
            "hotel-date-conflict",
            "attraction-closed",
            "user-preference-conflict",
            "ocr-missing-fields",
            "duplicate-order",
            "tool-timeout",
            "insufficient-evidence",
            "invalid-agent-output",
            "prompt-injection");
  }

  private JsonNode report(UUID runId) throws Exception {
    return read(
        mockMvc
            .perform(
                get("/api/v1/evaluations/runs/{runId}", runId)
                    .header("X-Operations-Token", OPERATIONS_TOKEN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray());
  }

  private JsonNode read(byte[] content) throws Exception {
    return objectMapper.readTree(content);
  }

  private void writeBaseline(JsonNode report) throws Exception {
    Path output = Path.of("target", "evaluation-reports", "phase-9-v1-baseline.json");
    Files.createDirectories(output.getParent());
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
  }
}
