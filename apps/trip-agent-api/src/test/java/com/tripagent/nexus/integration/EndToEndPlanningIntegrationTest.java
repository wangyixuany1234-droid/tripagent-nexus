package com.tripagent.nexus.integration;

import static com.tripagent.nexus.testsupport.OpenApiSchemaAssertions.assertErrorEnvelope;
import static com.tripagent.nexus.testsupport.OpenApiSchemaAssertions.assertResponse;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.RefreshMode.BEFORE_EACH_TEST_METHOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.application.document.DocumentParseCoordinator;
import com.tripagent.nexus.application.task.TaskEventStream;
import com.tripagent.nexus.domain.model.DomainEnums.BookingType;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.infrastructure.persistence.entity.DocumentEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.DocumentRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TaskRecordRepository;
import com.tripagent.nexus.integration.document.AiDocumentParserClient;
import com.tripagent.nexus.integration.document.DocumentParseEvents.Requested;
import com.tripagent.nexus.integration.document.DocumentParseEvents.RequestedPayload;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult.ExtractedBooking;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult.FieldEvidence;
import com.tripagent.nexus.skill.McpToolGateway;
import com.tripagent.nexus.skill.McpToolName;
import com.tripagent.nexus.skill.McpToolResult;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
    properties = {
      "app.agents.enabled=true",
      "app.planning.enabled=true",
      "app.planning.provider-mode=MOCK",
      "app.planning.default-commute-minutes=60",
      "app.mcp.service-token=test-mcp-token",
      "app.mcp.provider-mode=MOCK",
      "app.health.kafka.enabled=false",
      "app.health.ai-retrieval.enabled=false"
    })
@AutoConfigureMockMvc
@ActiveProfiles({"test", "mock-model"})
@AutoConfigureEmbeddedDatabase(
    provider = EMBEDDED,
    type = POSTGRES,
    refresh = BEFORE_EACH_TEST_METHOD)
class EndToEndPlanningIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DocumentParseCoordinator documentParseCoordinator;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private TaskRecordRepository taskRepository;
  @Autowired private TaskEventStream eventStream;
  @MockitoBean private AiDocumentParserClient parserClient;

  @Test
  void beijingPlanningCreatesAlternativesRepairsConflictAndVersionsReplan() throws Exception {
    UUID tripId = createBeijingTrip();
    attachCorrectedFlightFixture(tripId);

    JsonNode accepted =
        read(
            mockMvc
                .perform(
                    post("/api/v1/plans")
                        .header("Idempotency-Key", "beijing-plan-generate-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsBytes(
                                Map.of("tripId", tripId, "title", "Beijing three-day plan"))))
                .andExpect(status().isAccepted())
                .andReturn());
    assertResponse("TaskAccepted", accepted);
    UUID taskId = UUID.fromString(accepted.path("taskId").asText());
    awaitTask(taskId);

    JsonNode task =
        read(
            mockMvc
                .perform(get("/api/v1/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.resultResourceIds.length()").value(2))
                .andReturn());
    assertResponse("TaskRecord", task);
    assertSseMatchesTerminalPolling(taskId, task);
    mockMvc
        .perform(get("/api/v1/tasks/{taskId}/agents", taskId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(8))
        .andExpect(jsonPath("$.items[0].agentName").value("RequirementAgent"))
        .andExpect(jsonPath("$.items[0].status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.items[0].summary").isNotEmpty())
        .andExpect(jsonPath("$.items[7].agentName").value("CoordinatorAgent"))
        .andExpect(jsonPath("$.items[7].durationMs").isNumber());
    List<UUID> resultIds = uuidValues(task.path("resultResourceIds"));
    JsonNode planList =
        read(
            mockMvc
                .perform(get("/api/v1/trips/{tripId}/plans", tripId))
                .andExpect(status().isOk())
                .andReturn());
    assertResponse("TravelPlanList", planList);
    JsonNode plans = planList.path("items");

    assertThat(plans).hasSize(3);
    assertThat(textValues(plans, "alternativeKey")).contains("balanced", "low-transit");
    JsonNode original = find(plans, "balanced", 1);
    JsonNode repaired = findById(plans, resultIds.getFirst());
    JsonNode lowTransit = findById(plans, resultIds.get(1));
    assertThat(original.path("status").asText()).isEqualTo("PARTIAL");
    assertThat(conflictStatus(original, "COMMUTE_TIME_INSUFFICIENT")).isEqualTo("OPEN");
    assertThat(repaired.path("version").asInt()).isEqualTo(2);
    assertThat(repaired.path("autoReplanned").asBoolean()).isTrue();
    assertThat(conflictStatus(repaired, "COMMUTE_TIME_INSUFFICIENT")).isEqualTo("RESOLVED");
    assertThat(repaired.path("budgetSummary").path("totalBudget").decimalValue())
        .isEqualByComparingTo("5000.0000");
    assertThat(repaired.path("budgetSummary").path("dailyBudgets")).hasSize(3);
    assertThat(repaired.path("evidence")).isNotEmpty();
    assertThat(findItem(repaired, "ATTRACTION").at("/location/latitude").decimalValue())
        .isEqualByComparingTo("39.9163447");
    assertThat(findItem(repaired, "DINING").at("/location/longitude").decimalValue())
        .isEqualByComparingTo("116.4175710");
    assertThat(lowTransit.path("strategy").asText()).isEqualTo("LOW_TRANSIT");
    assertThat(lowTransit.path("days")).isNotEqualTo(repaired.path("days"));
    assertThat(eventStream.replay(taskId, null))
        .extracting(event -> event.stage())
        .contains(
            "REQUIREMENTS_LOADED",
            "RESEARCH_READY",
            "AGENTS_RUNNING",
            "CANDIDATES_ASSEMBLED",
            "RULE_VALIDATION",
            "LOCAL_REPLAN",
            "COMPLETED");

    UUID diningId = findItemId(repaired, "DINING");
    UUID lockedTransportId = findItemId(repaired, "TRANSPORT");
    JsonNode replanAccepted =
        read(
            mockMvc
                .perform(
                    post("/api/v1/plans/{planId}/replan", repaired.path("planId").asText())
                        .header("Idempotency-Key", "beijing-plan-replan-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsBytes(
                                Map.ofEntries(
                                    Map.entry("reason", "Reduce dining cost"),
                                    Map.entry("lockedItemIds", List.of(lockedTransportId)),
                                    Map.entry("removedItemIds", List.of(diningId)),
                                    Map.entry("adjustedTotalBudget", new BigDecimal("4500.0000")),
                                    Map.entry("adjustedPace", "RELAXED"),
                                    Map.entry("addedPreferences", List.of("hutong history")),
                                    Map.entry(
                                        "naturalLanguageRequest",
                                        "Keep the confirmed flight and remove dinner.")))))
                .andExpect(status().isAccepted())
                .andReturn());
    assertResponse("TaskAccepted", replanAccepted);
    UUID replanTaskId = UUID.fromString(replanAccepted.path("taskId").asText());
    awaitTask(replanTaskId);
    JsonNode replanTask =
        read(
            mockMvc
                .perform(get("/api/v1/tasks/{taskId}", replanTaskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andReturn());
    UUID replannedId = UUID.fromString(replanTask.path("resultResourceIds").get(0).asText());
    JsonNode replanned =
        read(
            mockMvc
                .perform(get("/api/v1/plans/{planId}", replannedId))
                .andExpect(status().isOk())
                .andReturn());
    assertResponse("TravelPlan", replanned);

    assertThat(replanned.path("version").asInt()).isGreaterThan(repaired.path("version").asInt());
    assertThat(replanned.path("previousPlanId").asText()).isNotBlank();
    assertThat(replanned.path("budgetSummary").path("totalBudget").decimalValue())
        .isEqualByComparingTo("4500.0000");
    assertThat(allItemIds(replanned)).contains(lockedTransportId).doesNotContain(diningId);
    assertThat(openConflictCodes(replanned))
        .doesNotContain(
            "TIME_OVERLAP",
            "COMMUTE_TIME_INSUFFICIENT",
            "HOTEL_CHECKIN_TIME_CONFLICT",
            "ATTRACTION_OUTSIDE_OPENING_HOURS");
    assertThat(replanned.path("replanInput").path("naturalLanguageRequest").asText())
        .contains("confirmed flight");
    assertThat(replanned.path("changeSummary").asText()).contains("removed");

    byte[] confirmRequest =
        objectMapper.writeValueAsBytes(
            Map.of("expectedVersion", replanned.path("version").asInt()));
    MvcResult confirmed =
        mockMvc
            .perform(
                post("/api/v1/plans/{planId}/confirm", replannedId)
                    .header("Idempotency-Key", "beijing-plan-confirm-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(confirmRequest))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andReturn();
    assertResponse("TravelPlan", read(confirmed));
    MvcResult repeatedConfirmation =
        mockMvc
            .perform(
                post("/api/v1/plans/{planId}/confirm", replannedId)
                    .header("Idempotency-Key", "beijing-plan-confirm-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(confirmRequest))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(read(repeatedConfirmation)).isEqualTo(read(confirmed));
    MvcResult conflictingConfirmation =
        mockMvc
            .perform(
                post("/api/v1/plans/{planId}/confirm", replannedId)
                    .header("Idempotency-Key", "beijing-plan-confirm-0002")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(confirmRequest))
            .andExpect(status().isConflict())
            .andReturn();
    assertErrorEnvelope(read(conflictingConfirmation), "PLAN_NOT_CONFIRMABLE");

    mockMvc
        .perform(
            post("/api/v1/feedback")
                .header("Idempotency-Key", "beijing-feedback-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsBytes(
                        Map.of(
                            "tripId",
                            tripId,
                            "planId",
                            replannedId,
                            "rating",
                            5,
                            "comment",
                            "The corrected booking and relaxed replan are usable.",
                            "tags",
                            List.of("accepted", "low-transit")))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.tripId").value(tripId.toString()))
        .andExpect(jsonPath("$.planId").value(replannedId.toString()))
        .andExpect(jsonPath("$.rating").value(5));

    mockMvc
        .perform(
            get(
                "/api/v1/plans/{planId}/compare/{otherPlanId}",
                repaired.path("planId").asText(),
                replannedId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.planFamilyId").value(repaired.path("planFamilyId").asText()))
        .andExpect(jsonPath("$.removedItemIds[0]").value(diningId.toString()))
        .andExpect(jsonPath("$.budgetDelta").value(-200.0000));

    JsonNode originalAfterReplan =
        read(
            mockMvc
                .perform(get("/api/v1/plans/{planId}", repaired.path("planId").asText()))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(originalAfterReplan.path("version").asInt()).isEqualTo(2);
    assertThat(allItemIds(originalAfterReplan)).contains(diningId);
  }

  private UUID createBeijingTrip() throws Exception {
    JsonNode trip =
        read(
            mockMvc
                .perform(
                    post("/api/v1/trips")
                        .header("Idempotency-Key", "beijing-trip-create-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsBytes(
                                Map.of(
                                    "userId",
                                    UUID.fromString("018f0e31-6ec8-7a59-b871-0242ac120077"),
                                    "title",
                                    "Beijing three-day acceptance",
                                    "timeZone",
                                    "Asia/Shanghai"))))
                .andExpect(status().isCreated())
                .andReturn());
    UUID tripId = UUID.fromString(trip.path("tripId").asText());
    Map<String, Object> requirement =
        Map.ofEntries(
            Map.entry("origin", Map.of("name", "Shanghai")),
            Map.entry("destinations", List.of(Map.of("name", "Beijing"))),
            Map.entry("startDate", "2026-10-01"),
            Map.entry("endDate", "2026-10-03"),
            Map.entry(
                "travelers",
                List.of(
                    traveler("018f0e31-6ec8-7a59-b871-0242ac120078", "Adult 1"),
                    traveler("018f0e31-6ec8-7a59-b871-0242ac120079", "Adult 2"))),
            Map.entry("totalBudget", new BigDecimal("5000.0000")),
            Map.entry("currency", "CNY"),
            Map.entry("interests", List.of("history", "local food")),
            Map.entry("pace", "BALANCED"),
            Map.entry("mustVisit", List.of("Forbidden City")),
            Map.entry("avoid", List.of()),
            Map.entry("freeTextConstraints", "Prefer local food near each activity area."));
    mockMvc
        .perform(
            put("/api/v1/trips/{tripId}/requirements", tripId)
                .header("Idempotency-Key", "beijing-requirement-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(requirement)))
        .andExpect(status().isOk());
    return tripId;
  }

  private void attachCorrectedFlightFixture(UUID tripId) throws Exception {
    when(parserClient.parse(
            any(DocumentEntity.class),
            any(UUID.class),
            any(byte[].class),
            anyString(),
            anyString()))
        .thenReturn(incompleteFlightExtraction());
    Path fixture = findRepositoryRoot().resolve("qa/fixtures/documents/flight-confirmation.jpg");
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "flight-confirmation.jpg", "image/jpeg", Files.readAllBytes(fixture));
    JsonNode accepted =
        read(
            mockMvc
                .perform(
                    multipart("/api/v1/documents/parse")
                        .file(file)
                        .param("tripId", tripId.toString())
                        .header("Idempotency-Key", "beijing-flight-upload-0001"))
                .andExpect(status().isAccepted())
                .andReturn());
    UUID documentId = UUID.fromString(accepted.path("resourceId").asText());
    DocumentEntity document = documentRepository.findById(documentId).orElseThrow();
    Requested request =
        new Requested(
            UUID.randomUUID(),
            "document.parse.requested",
            "1.0.0",
            Instant.now(),
            UUID.fromString(accepted.path("taskId").asText()),
            UUID.fromString(accepted.path("taskId").asText()),
            accepted.path("traceId").asText(),
            tripId,
            UUID.fromString(accepted.path("taskId").asText()),
            "beijing-flight-upload-0001",
            new RequestedPayload(
                documentId,
                "BOOKING",
                document.getMediaType(),
                document.getSizeBytes(),
                document.getSha256(),
                document.getStorageKey(),
                "zh-CN",
                "1.0.0"));
    documentParseCoordinator.process(request);

    JsonNode parsedDocument =
        read(
            mockMvc
                .perform(get("/api/v1/documents/{documentId}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.bookings[0].reviewStatus").value("NEEDS_REVIEW"))
                .andReturn());
    UUID bookingId = UUID.fromString(parsedDocument.at("/bookings/0/bookingId").asText());
    UUID parseTaskId = UUID.fromString(accepted.path("taskId").asText());
    mockMvc
        .perform(get("/api/v1/tasks/{taskId}", parseTaskId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("WAITING_CONFIRMATION"));

    mockMvc
        .perform(
            patch("/api/v1/documents/{documentId}/bookings/{bookingId}", documentId, bookingId)
                .header("Idempotency-Key", "beijing-flight-correction-0001")
                .header("X-Actor-Id", "acceptance-user")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "corrections": [
                        {"field": "startTime", "value": "2026-09-30T22:00:00Z"},
                        {"field": "endTime", "value": "2026-10-01T02:00:00Z"}
                      ],
                      "reason": "Verified against the redacted booking fixture"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviewStatus").value("CORRECTED"))
        .andExpect(jsonPath("$.corrections.length()").value(2));
    mockMvc
        .perform(get("/api/v1/tasks/{taskId}", parseTaskId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));
  }

  private static BookingExtractionResult incompleteFlightExtraction() {
    List<FieldEvidence> evidence =
        List.of(
            evidence("type", "FLIGHT"),
            evidence("origin", "Shanghai"),
            evidence("destination", "Beijing"),
            evidence("transportNumber", "MU5101"),
            evidence("confirmationNumber", "REDACTED-FLIGHT-001"));
    return new BookingExtractionResult(
        List.of(
            new ExtractedBooking(
                BookingType.FLIGHT,
                "Fixture Airline",
                "Shanghai",
                "Beijing",
                null,
                null,
                "MU5101",
                "REDACTED-FLIGHT-001",
                null,
                null,
                2,
                new BigDecimal("1000.0000"),
                "CNY",
                new BigDecimal("0.8500"),
                List.of("startTime", "endTime"),
                evidence)),
        List.of(),
        new BigDecimal("0.8500"),
        "MOCK",
        "fixture-1.0.0",
        "fixture");
  }

  private void assertSseMatchesTerminalPolling(UUID taskId, JsonNode polledTask) throws Exception {
    MvcResult stream =
        mockMvc
            .perform(
                get("/api/v1/tasks/{taskId}/events", taskId).accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(request().asyncStarted())
            .andReturn();
    MvcResult completed =
        mockMvc
            .perform(asyncDispatch(stream))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

    String events = completed.getResponse().getContentAsString();
    assertThat(polledTask.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(events)
        .contains("\"taskId\":\"" + taskId + "\"")
        .contains("\"stage\":\"COMPLETED\"")
        .contains("\"progress\":100");
  }

  private static FieldEvidence evidence(String field, String text) {
    return new FieldEvidence(field, new BigDecimal("0.9900"), text, null);
  }

  private void awaitTask(UUID taskId) {
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(taskRepository.findById(taskId).orElseThrow().getStatus())
                    .isEqualTo(TaskStatus.SUCCEEDED));
  }

  private static Map<String, Object> traveler(String id, String name) {
    return Map.of(
        "travelerId",
        UUID.fromString(id),
        "displayName",
        name,
        "type",
        "ADULT",
        "accessibilityNeeds",
        List.of());
  }

  private static JsonNode find(JsonNode plans, String alternativeKey, int version) {
    for (JsonNode plan : plans) {
      if (alternativeKey.equals(plan.path("alternativeKey").asText())
          && version == plan.path("version").asInt()) {
        return plan;
      }
    }
    throw new AssertionError("Plan was not found");
  }

  private static JsonNode findById(JsonNode plans, UUID planId) {
    for (JsonNode plan : plans) {
      if (planId.toString().equals(plan.path("planId").asText())) {
        return plan;
      }
    }
    throw new AssertionError("Plan was not found");
  }

  private static String conflictStatus(JsonNode plan, String code) {
    for (JsonNode conflict : plan.path("conflicts")) {
      if (code.equals(conflict.path("code").asText())) {
        return conflict.path("status").asText();
      }
    }
    return "";
  }

  private static Set<String> openConflictCodes(JsonNode plan) {
    Set<String> codes = new java.util.HashSet<>();
    for (JsonNode conflict : plan.path("conflicts")) {
      if ("OPEN".equals(conflict.path("status").asText())) {
        codes.add(conflict.path("code").asText());
      }
    }
    return Set.copyOf(codes);
  }

  private static UUID findItemId(JsonNode plan, String type) {
    return UUID.fromString(findItem(plan, type).path("itemId").asText());
  }

  private static JsonNode findItem(JsonNode plan, String type) {
    for (JsonNode day : plan.path("days")) {
      for (JsonNode item : day.path("items")) {
        if (type.equals(item.path("type").asText())) {
          return item;
        }
      }
    }
    throw new AssertionError("Item was not found: " + type);
  }

  private static Set<UUID> allItemIds(JsonNode plan) {
    Set<UUID> ids = new java.util.LinkedHashSet<>();
    plan.path("days")
        .forEach(
            day ->
                day.path("items")
                    .forEach(item -> ids.add(UUID.fromString(item.path("itemId").asText()))));
    return Set.copyOf(ids);
  }

  private static List<String> textValues(JsonNode array, String field) {
    List<String> values = new ArrayList<>();
    array.forEach(item -> values.add(item.path(field).asText()));
    return List.copyOf(values);
  }

  private static List<UUID> uuidValues(JsonNode array) {
    List<UUID> values = new ArrayList<>();
    array.forEach(item -> values.add(UUID.fromString(item.asText())));
    return List.copyOf(values);
  }

  private JsonNode read(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsByteArray());
  }

  private static Path findRepositoryRoot() {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      if (Files.exists(directory.resolve("compose.yaml"))) {
        return directory;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException("Could not locate repository root");
  }

  @TestConfiguration
  static class ToolGatewayConfiguration {

    @Bean
    @Primary
    McpToolGateway testMcpToolGateway(ObjectMapper objectMapper) {
      return new McpToolGateway() {
        @Override
        public Set<String> discover(AgentName agentName) {
          return McpToolName.names();
        }

        @Override
        public McpToolResult call(
            AgentName agentName, McpToolName toolName, Map<String, Object> arguments) {
          var data = objectMapper.createObjectNode();
          data.put("tool", toolName.value());
          return new McpToolResult(
              McpToolResult.Status.SUCCEEDED,
              McpToolResult.ProviderMode.MOCK,
              data,
              List.of("Mock MCP provider is active."));
        }
      };
    }
  }
}
