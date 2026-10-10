package com.tripagent.nexus.integration;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.RefreshMode.BEFORE_EACH_TEST_METHOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.application.service.UserMemoryCleanupService;
import com.tripagent.nexus.domain.model.Phase14Models.PreferenceSource;
import com.tripagent.nexus.infrastructure.persistence.entity.UserPreferenceEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.UserPreferenceRepository;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(
    provider = EMBEDDED,
    type = POSTGRES,
    refresh = BEFORE_EACH_TEST_METHOD)
class Phase14SupportIntegrationTest {

  private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000014");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserPreferenceRepository preferenceRepository;
  @MockitoBean private UserMemoryCleanupService cleanupService;

  @Test
  void workbenchFiltersTasksAndSystemStatusDoesNotExposeConnections() throws Exception {
    UUID tripId = createTripAndRequirement();
    JsonNode accepted =
        read(
            mockMvc
                .perform(
                    post("/api/v1/plans")
                        .header("Idempotency-Key", "phase14-plan-create-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("tripId", tripId))))
                .andExpect(status().isAccepted())
                .andReturn());

    mockMvc
        .perform(
            get("/api/v1/workbench/tasks")
                .param("userId", USER_ID.toString())
                .param("destination", "北京")
                .param("startDate", "2026-10-01")
                .param("endDate", "2026-10-03"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].taskId").value(accepted.get("taskId").asText()))
        .andExpect(jsonPath("$.items[0].destination").value("北京"))
        .andExpect(jsonPath("$.items[0].status").value("QUEUED"));

    MvcResult statusResult =
        mockMvc
            .perform(get("/api/v1/system/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dependencies").isArray())
            .andExpect(jsonPath("$.capabilities").isArray())
            .andReturn();
    String body = statusResult.getResponse().getContentAsString();
    assertThat(body).doesNotContain("jdbc:", "password", "localhost", "token");

    mockMvc
        .perform(get("/api/v1/developer/traces/11111111111111111111111111111111"))
        .andExpect(status().isForbidden());
  }

  @Test
  void preferencesAreUserControlledAndFeedbackInferenceCanBeRejected() throws Exception {
    UUID tripId = createTripAndRequirement();
    JsonNode created =
        read(
            mockMvc
                .perform(
                    post("/api/v1/users/{userId}/preferences", USER_ID)
                        .header("Idempotency-Key", "phase14-preference-create-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsBytes(
                                Map.of("category", "饮食", "value", "偏好清淡口味"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("USER"))
                .andReturn());
    UUID preferenceId = UUID.fromString(created.get("preferenceId").asText());

    mockMvc
        .perform(
            put("/api/v1/users/{userId}/preferences/{preferenceId}", USER_ID, preferenceId)
                .header("Idempotency-Key", "phase14-preference-update-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsBytes(Map.of("category", "饮食", "value", "偏好清淡且少辣"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.value").value("偏好清淡且少辣"));

    mockMvc
        .perform(
            post("/api/v1/feedback")
                .header("Idempotency-Key", "phase14-feedback-create-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsBytes(
                        Map.of(
                            "tripId",
                            tripId,
                            "targetType",
                            "PLACE",
                            "targetId",
                            "place-forbidden-city",
                            "sentiment",
                            "LIKE",
                            "rating",
                            5,
                            "comment",
                            "喜欢历史建筑与步行游览",
                            "tags",
                            List.of("历史建筑")))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.memoryAccepted").value(true));

    UserPreferenceEntity inferred =
        preferenceRepository.findByUserIdOrderByUpdatedAtDesc(USER_ID).stream()
            .filter(item -> item.getSource() == PreferenceSource.INFERRED)
            .findFirst()
            .orElseThrow();
    mockMvc
        .perform(
            post(
                    "/api/v1/users/{userId}/preferences/{preferenceId}/reject",
                    USER_ID,
                    inferred.getPreferenceId())
                .header("Idempotency-Key", "phase14-preference-reject-0001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
    verify(cleanupService).delete(USER_ID, inferred.getPreferenceId());

    mockMvc
        .perform(
            delete("/api/v1/users/{userId}/preferences/{preferenceId}", USER_ID, preferenceId)
                .header("Idempotency-Key", "phase14-preference-delete-0001"))
        .andExpect(status().isNoContent());
    verify(cleanupService).delete(USER_ID, preferenceId);
  }

  private UUID createTripAndRequirement() throws Exception {
    JsonNode trip =
        read(
            mockMvc
                .perform(
                    post("/api/v1/trips")
                        .header("Idempotency-Key", "phase14-trip-create-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsBytes(
                                Map.of(
                                    "userId",
                                    USER_ID,
                                    "title",
                                    "北京三日游",
                                    "timeZone",
                                    "Asia/Shanghai"))))
                .andExpect(status().isCreated())
                .andReturn());
    UUID tripId = UUID.fromString(trip.get("tripId").asText());
    mockMvc
        .perform(
            put("/api/v1/trips/{tripId}/requirements", tripId)
                .header("Idempotency-Key", "phase14-requirement-save-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsBytes(
                        Map.ofEntries(
                            Map.entry("origin", Map.of("name", "上海")),
                            Map.entry("destinations", List.of(Map.of("name", "北京"))),
                            Map.entry("startDate", "2026-10-01"),
                            Map.entry("endDate", "2026-10-03"),
                            Map.entry(
                                "travelers",
                                List.of(
                                    Map.of(
                                        "travelerId",
                                        UUID.randomUUID(),
                                        "displayName",
                                        "成人",
                                        "type",
                                        "ADULT",
                                        "accessibilityNeeds",
                                        List.of()))),
                            Map.entry("totalBudget", 6000),
                            Map.entry("currency", "CNY"),
                            Map.entry("interests", List.of("历史")),
                            Map.entry("pace", "BALANCED"),
                            Map.entry("mustVisit", List.of("故宫")),
                            Map.entry("avoid", List.of()),
                            Map.entry("freeTextConstraints", "")))))
        .andExpect(status().isOk());
    return tripId;
  }

  private JsonNode read(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsByteArray());
  }
}
