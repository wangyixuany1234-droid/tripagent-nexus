package com.tripagent.nexus.integration;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.RefreshMode.BEFORE_EACH_TEST_METHOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.application.task.TaskEventStream;
import com.tripagent.nexus.domain.model.TaskEvent;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelPlanEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.DocumentRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TaskRecordRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelPlanRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TripRepository;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(
    provider = EMBEDDED,
    type = POSTGRES,
    refresh = BEFORE_EACH_TEST_METHOD)
class TripApiPostgresIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private TripRepository tripRepository;

  @Autowired private DocumentRepository documentRepository;

  @Autowired private TravelPlanRepository planRepository;

  @Autowired private TaskRecordRepository taskRepository;

  @Autowired private TaskEventStream eventStream;

  @Test
  void publicHealthReportsTheConfiguredProviderMode() throws Exception {
    mockMvc
        .perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.service").value("trip-agent-api"))
        .andExpect(jsonPath("$.apiVersion").value("v1"))
        .andExpect(jsonPath("$.providerMode").value("MOCK"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty());
  }

  @Test
  void prometheusEndpointExposesJvmMetrics() throws Exception {
    mockMvc
        .perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
        .andExpect(content().string(containsString("jvm_memory_used_bytes")));
  }

  @Test
  void migratesAnEmptyPostgresDatabase() {
    List<String> tables =
        jdbcTemplate.queryForList(
            """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'public'
            """,
            String.class);

    assertThat(tables)
        .contains(
            "platform_health_marker",
            "user_profile",
            "trip",
            "travel_requirement",
            "uploaded_document",
            "parsed_booking",
            "travel_plan",
            "task_record",
            "task_event",
            "agent_result",
            "feedback",
            "user_preference",
            "idempotency_record",
            "booking_correction_audit",
            "event_outbox");
  }

  @Test
  void tripRequirementAndPlanCrudAreIdempotent() throws Exception {
    JsonNode firstTrip = createTrip("trip-create-key-0001", "Tokyo family trip");
    JsonNode repeatedTrip = createTrip("trip-create-key-0001", "Tokyo family trip");
    UUID tripId = UUID.fromString(firstTrip.get("tripId").asText());

    assertThat(repeatedTrip.get("tripId").asText()).isEqualTo(firstTrip.get("tripId").asText());
    assertThat(tripRepository.count()).isEqualTo(1);

    mockMvc
        .perform(
            post("/api/v1/trips")
                .header("Idempotency-Key", "trip-create-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsBytes(
                        Map.of(
                            "userId",
                            UUID.randomUUID(),
                            "title",
                            "Different request",
                            "timeZone",
                            "Asia/Tokyo"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

    saveRequirement(tripId);

    byte[] planRequest =
        objectMapper.writeValueAsBytes(Map.of("tripId", tripId, "title", "Balanced Tokyo"));
    MvcResult accepted =
        mockMvc
            .perform(
                post("/api/v1/plans")
                    .header("Idempotency-Key", "plan-create-key-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(planRequest))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andReturn();
    MvcResult repeated =
        mockMvc
            .perform(
                post("/api/v1/plans")
                    .header("Idempotency-Key", "plan-create-key-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(planRequest))
            .andExpect(status().isAccepted())
            .andReturn();

    JsonNode acceptedBody = read(accepted);
    JsonNode repeatedBody = read(repeated);
    UUID planId = UUID.fromString(acceptedBody.get("resourceId").asText());
    UUID taskId = UUID.fromString(acceptedBody.get("taskId").asText());
    assertThat(repeatedBody).isEqualTo(acceptedBody);
    assertThat(planRepository.count()).isEqualTo(1);
    assertThat(taskRepository.count()).isEqualTo(1);

    mockMvc
        .perform(get("/api/v1/plans/{planId}", planId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("GENERATING"))
        .andExpect(jsonPath("$.budgetSummary.currency").value("CNY"));
    mockMvc
        .perform(get("/api/v1/trips/{tripId}/plans", tripId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));
    mockMvc
        .perform(get("/api/v1/tasks/{taskId}", taskId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("QUEUED"));
  }

  @Test
  void documentFeedbackConfirmationAndTaskEventReplayWork() throws Exception {
    UUID tripId =
        UUID.fromString(createTrip("trip-create-key-0002", "Osaka trip").get("tripId").asText());
    saveRequirement(tripId);

    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "ticket.png",
            "image/png",
            Base64.getDecoder()
                .decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk"
                        + "+A8AAQUBAScY42YAAAAASUVORK5CYII="));
    MvcResult parseAccepted =
        mockMvc
            .perform(
                multipart("/api/v1/documents/parse")
                    .file(file)
                    .param("tripId", tripId.toString())
                    .header("Idempotency-Key", "document-key-000001"))
            .andExpect(status().isAccepted())
            .andReturn();
    MvcResult parseRepeated =
        mockMvc
            .perform(
                multipart("/api/v1/documents/parse")
                    .file(file)
                    .param("tripId", tripId.toString())
                    .header("Idempotency-Key", "document-key-000001"))
            .andExpect(status().isAccepted())
            .andReturn();
    JsonNode parseBody = read(parseAccepted);
    assertThat(read(parseRepeated)).isEqualTo(parseBody);
    assertThat(documentRepository.count()).isEqualTo(1);
    mockMvc
        .perform(get("/api/v1/documents/{documentId}", parseBody.get("resourceId").asText()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("QUEUED"));

    JsonNode planTask = createPlan(tripId, "plan-create-key-0002");
    UUID taskId = UUID.fromString(planTask.get("taskId").asText());
    List<TaskEvent> events = eventStream.replay(taskId, null);
    assertThat(events).hasSize(2);
    assertThat(eventStream.replay(taskId, events.getFirst().eventId()))
        .extracting(TaskEvent::eventId)
        .containsExactly(events.get(1).eventId());

    MvcResult stream =
        mockMvc
            .perform(
                get("/api/v1/tasks/{taskId}/events", taskId)
                    .header("Last-Event-ID", events.getFirst().eventId()))
            .andExpect(request().asyncStarted())
            .andReturn();
    mockMvc
        .perform(
            post("/api/v1/tasks/{taskId}/cancel", taskId)
                .header("Idempotency-Key", "task-cancel-key-001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    assertThat(stream.getResponse().getContentAsString())
        .contains(events.get(1).eventId().toString())
        .contains("event:task-progress");

    JsonNode confirmationTask = createPlan(tripId, "plan-create-key-0003");
    UUID confirmablePlanId = UUID.fromString(confirmationTask.get("resourceId").asText());
    TravelPlanEntity confirmable = planRepository.findById(confirmablePlanId).orElseThrow();
    confirmable.markReady("Ready for confirmation", Instant.now());
    planRepository.saveAndFlush(confirmable);
    mockMvc
        .perform(
            post("/api/v1/plans/{planId}/confirm", confirmablePlanId)
                .header("Idempotency-Key", "plan-confirm-key-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));

    mockMvc
        .perform(
            post("/api/v1/feedback")
                .header("Idempotency-Key", "feedback-create-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsBytes(
                        Map.of(
                            "tripId",
                            tripId,
                            "planId",
                            confirmablePlanId,
                            "rating",
                            5,
                            "comment",
                            "Good route balance",
                            "tags",
                            Set.of("balanced")))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.rating").value(5));
  }

  private JsonNode createTrip(String idempotencyKey, String title) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/trips")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsBytes(
                            Map.of(
                                "userId",
                                UUID.fromString("018f0e31-6ec8-7a59-b871-0242ac120001"),
                                "title",
                                title,
                                "timeZone",
                                "Asia/Tokyo"))))
            .andExpect(status().isCreated())
            .andReturn();
    return read(result);
  }

  private void saveRequirement(UUID tripId) throws Exception {
    Map<String, Object> request =
        Map.ofEntries(
            Map.entry("origin", Map.of("name", "Shanghai")),
            Map.entry("destinations", List.of(Map.of("name", "Tokyo"))),
            Map.entry("startDate", "2026-10-01"),
            Map.entry("endDate", "2026-10-05"),
            Map.entry(
                "travelers",
                List.of(
                    Map.of(
                        "travelerId",
                        UUID.fromString("018f0e31-6ec8-7a59-b871-0242ac120010"),
                        "displayName",
                        "Traveler 1",
                        "type",
                        "ADULT",
                        "accessibilityNeeds",
                        List.of()))),
            Map.entry("totalBudget", new BigDecimal("12000.0000")),
            Map.entry("currency", "CNY"),
            Map.entry("interests", List.of("food", "culture")),
            Map.entry("pace", "BALANCED"),
            Map.entry("mustVisit", List.of("Senso-ji")),
            Map.entry("avoid", List.of("overnight bus")),
            Map.entry("freeTextConstraints", "No activities before 09:00"));
    mockMvc
        .perform(
            put("/api/v1/trips/{tripId}/requirements", tripId)
                .header("Idempotency-Key", "requirement-key-" + tripId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalBudget").value(12000.0000));
  }

  private JsonNode createPlan(UUID tripId, String idempotencyKey) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/plans")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(Map.of("tripId", tripId))))
            .andExpect(status().isAccepted())
            .andReturn();
    return read(result);
  }

  private JsonNode read(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsByteArray());
  }
}
