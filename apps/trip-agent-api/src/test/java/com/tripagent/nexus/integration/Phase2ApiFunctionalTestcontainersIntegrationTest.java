package com.tripagent.nexus.integration;

import static com.tripagent.nexus.testsupport.OpenApiSchemaAssertions.assertErrorEnvelope;
import static com.tripagent.nexus.testsupport.OpenApiSchemaAssertions.assertResponse;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.application.service.TaskApplicationService;
import com.tripagent.nexus.application.service.UserMemoryCleanupService;
import com.tripagent.nexus.application.task.TaskEventStream;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.domain.model.TaskEvent;
import com.tripagent.nexus.infrastructure.persistence.repository.UserPreferenceRepository;
import com.tripagent.nexus.testsupport.TestJwts;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "app.upload.max-size=1MB",
      "spring.servlet.multipart.max-file-size=2MB",
      "spring.servlet.multipart.max-request-size=2MB",
      "app.security.enabled=true",
      "app.security.issuer-uri=" + TestJwts.ISSUER,
      "app.security.audience=" + TestJwts.AUDIENCE
    })
@ActiveProfiles("test")
@Import(TestJwts.TestJwtSecurityConfig.class)
@Testcontainers
@SuppressWarnings("resource")
class Phase2ApiFunctionalTestcontainersIntegrationTest {

  private static final UUID USER_A = UUID.fromString("20000000-0000-0000-0000-000000000021");
  private static final UUID USER_B = UUID.fromString("20000000-0000-0000-0000-000000000022");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.6-alpine"));

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @LocalServerPort private int port;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TaskApplicationService taskService;
  @Autowired private TaskEventStream eventStream;
  @Autowired private UserPreferenceRepository preferenceRepository;
  @MockitoBean private UserMemoryCleanupService cleanupService;

  @Test
  void tripEndpointsEnforceValidationPrecisionUtcAndOwnership() throws Exception {
    Response created = createTrip(USER_A, "北京三日游", "Asia/Shanghai");
    JsonNode trip = json(created);
    assertResponse("Trip", trip);
    UUID tripId = UUID.fromString(trip.path("tripId").asText());
    Instant createdAt = Instant.parse(trip.path("createdAt").asText());
    assertThat(createdAt.atOffset(ZoneOffset.UTC).getOffset()).isEqualTo(ZoneOffset.UTC);
    assertThat(createdAt.atZone(ZoneId.of("Asia/Shanghai")).toInstant()).isEqualTo(createdAt);

    Response owned = requestAs(USER_A).get("/api/v1/trips/{tripId}", tripId);
    owned.then().statusCode(200);
    assertResponse("Trip", json(owned));
    assertError(requestAs(USER_B).get("/api/v1/trips/{tripId}", tripId), 404, "RESOURCE_NOT_FOUND");

    Map<String, Object> valid = requirement("1234.5678", "CNY");
    Response saved = saveRequirement(tripId, valid);
    saved.then().statusCode(200);
    JsonNode requirement = json(saved);
    assertResponse("TravelRequirement", requirement);
    assertThat(requirement.path("totalBudget").decimalValue())
        .isEqualByComparingTo(new BigDecimal("1234.5678"));

    Map<String, Object> stringBudget = requirement("1234.5678", "CNY");
    stringBudget.put("totalBudget", "1234.5678");
    assertError(saveRequirement(tripId, stringBudget), 400, "REQUEST_INVALID");

    assertError(saveRequirement(tripId, requirement("0.0000", "CNY")), 422, "TRIP_BUDGET_INVALID");
    assertError(saveRequirement(tripId, requirement("-0.0001", "CNY")), 422, "VALIDATION_FAILED");
    assertError(
        saveRequirement(tripId, requirement("1000000000000000.0000", "CNY")),
        422,
        "VALIDATION_FAILED");
    assertError(
        saveRequirement(tripId, requirement("1000.0000", "ZZZ")), 422, "TRIP_CURRENCY_UNSUPPORTED");

    Map<String, Object> reversedDates = requirement("1000.0000", "CNY");
    reversedDates.put("startDate", "2026-10-04");
    reversedDates.put("endDate", "2026-10-03");
    assertError(saveRequirement(tripId, reversedDates), 422, "TRIP_DATE_RANGE_INVALID");

    Map<String, Object> longConstraint = requirement("1000.0000", "CNY");
    longConstraint.put("freeTextConstraints", "x".repeat(5001));
    assertError(saveRequirement(tripId, longConstraint), 422, "VALIDATION_FAILED");

    assertError(
        postJson(
            "/api/v1/trips",
            Map.of("title", "Missing user", "timeZone", "Asia/Shanghai"),
            key("trip-missing")),
        422,
        "VALIDATION_FAILED");
    Map<String, Object> unknownField = new LinkedHashMap<>();
    unknownField.put("userId", USER_A);
    unknownField.put("title", "Unknown field");
    unknownField.put("timeZone", "Asia/Shanghai");
    unknownField.put("internalState", "must-not-be-accepted");
    assertError(
        postJson("/api/v1/trips", unknownField, key("trip-unknown")), 400, "REQUEST_INVALID");
  }

  @Test
  void documentEndpointsAcceptSupportedFilesAndRejectUnsafeBoundaries() throws Exception {
    UUID tripId =
        UUID.fromString(json(createTrip(USER_A, "订单解析", "Asia/Shanghai")).path("tripId").asText());
    byte[] png = Files.readAllBytes(fixture("hotel-confirmation.png"));
    byte[] pdf = Files.readAllBytes(fixture("duplicate-orders.pdf"));

    Response image = upload(tripId, "hotel-confirmation.png", "image/png", png);
    image.then().statusCode(202);
    JsonNode acceptedImage = json(image);
    assertResponse("TaskAccepted", acceptedImage);

    Response duplicate = upload(tripId, "hotel-copy.png", "image/png", png);
    duplicate.then().statusCode(202);
    assertThat(json(duplicate).path("resourceId").asText())
        .isEqualTo(acceptedImage.path("resourceId").asText());

    Response parsedDocument =
        request().get("/api/v1/documents/{documentId}", acceptedImage.path("resourceId").asText());
    parsedDocument.then().statusCode(200);
    assertResponse("ParsedDocument", json(parsedDocument));

    Response pdfResponse = upload(tripId, "duplicate-orders.pdf", "application/pdf", pdf);
    pdfResponse.then().statusCode(202);
    assertResponse("TaskAccepted", json(pdfResponse));

    assertError(upload(tripId, "spoofed.jpg", "image/jpeg", png), 415, "UPLOAD_MIME_MISMATCH");
    assertError(upload(tripId, "empty.png", "image/png", new byte[0]), 413, "UPLOAD_SIZE_INVALID");
    assertError(
        upload(tripId, "oversized.png", "image/png", new byte[1024 * 1024 + 1]),
        413,
        "UPLOAD_SIZE_INVALID");
  }

  @Test
  void taskEndpointsPreserveStateOrderCancellationAndSseResume() throws Exception {
    UUID taskId = taskService.create(TaskType.EVALUATION_REPLAY, UUID.randomUUID()).taskId();
    taskService.transition(taskId, TaskStatus.RUNNING, "STEP_ONE", 20, "First step");
    taskService.transition(taskId, TaskStatus.RUNNING, "STEP_TWO", 60, "Second step");
    List<TaskEvent> beforeCompletion = eventStream.replay(taskId, null);
    UUID reconnectAfter = beforeCompletion.get(2).eventId();
    taskService.transition(taskId, TaskStatus.SUCCEEDED, "COMPLETED", 100, "Completed");

    Response polled = requestAdmin().get("/api/v1/tasks/{taskId}", taskId);
    polled.then().statusCode(200);
    assertResponse("TaskRecord", json(polled));
    assertThat(json(polled).path("status").asText()).isEqualTo("SUCCEEDED");

    Response resumed =
        requestAdmin()
            .accept("text/event-stream")
            .header("Last-Event-ID", reconnectAfter)
            .get("/api/v1/tasks/{taskId}/events", taskId);
    resumed.then().statusCode(200);
    List<JsonNode> replayed = sseData(resumed.asString());
    assertThat(replayed)
        .extracting(node -> node.path("stage").asText())
        .containsExactly("STEP_TWO", "COMPLETED");
    replayed.forEach(event -> assertResponse("TaskEvent", event));

    assertError(
        requestAdmin()
            .header("Idempotency-Key", key("cancel-complete"))
            .post("/api/v1/tasks/{taskId}/cancel", taskId),
        409,
        "TASK_ALREADY_TERMINAL");
    assertError(
        requestAdmin().get("/api/v1/tasks/{taskId}", UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
    assertError(
        requestAdmin()
            .accept("text/event-stream, application/json")
            .header("Last-Event-ID", UUID.randomUUID())
            .get("/api/v1/tasks/{taskId}/events", taskId),
        410,
        "TASK_EVENT_GONE");

    UUID runningTask = taskService.create(TaskType.EVALUATION_REPLAY, UUID.randomUUID()).taskId();
    taskService.transition(runningTask, TaskStatus.RUNNING, "RUNNING", 10, "Running");
    Response cancelled =
        requestAdmin()
            .header("Idempotency-Key", key("cancel-running"))
            .post("/api/v1/tasks/{taskId}/cancel", runningTask);
    cancelled.then().statusCode(200);
    assertResponse("TaskRecord", json(cancelled));
    assertThat(json(cancelled).path("status").asText()).isEqualTo("CANCELLED");

    UUID invalidTask = taskService.create(TaskType.EVALUATION_REPLAY, UUID.randomUUID()).taskId();
    taskService.transition(invalidTask, TaskStatus.RUNNING, "RUNNING", 10, "Running");
    assertThatThrownBy(
            () -> taskService.transition(invalidTask, TaskStatus.QUEUED, "QUEUED", 20, "Invalid"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TASK_STATE_TRANSITION_INVALID");
  }

  @Test
  void feedbackAndPreferenceEndpointsAreIdempotentAndUserScoped() throws Exception {
    UUID tripId =
        UUID.fromString(json(createTrip(USER_A, "反馈测试", "Asia/Shanghai")).path("tripId").asText());
    Map<String, Object> feedbackBody =
        Map.of(
            "tripId", tripId,
            "targetType", "PLACE",
            "targetId", "place-forbidden-city",
            "sentiment", "LIKE",
            "rating", 5,
            "comment", "喜欢有证据支持的历史建筑推荐",
            "tags", List.of("历史建筑"));
    String feedbackKey = key("feedback");
    Response feedback = postJson("/api/v1/feedback", feedbackBody, feedbackKey);
    feedback.then().statusCode(201);
    assertResponse("Feedback", json(feedback));
    Response repeated = postJson("/api/v1/feedback", feedbackBody, feedbackKey);
    repeated.then().statusCode(201);
    assertThat(json(repeated).path("feedbackId").asText())
        .isEqualTo(json(feedback).path("feedbackId").asText());

    Response preference =
        postJson(
            "/api/v1/users/" + USER_A + "/preferences",
            Map.of("category", "diet", "value", "less spicy"),
            key("preference"));
    preference.then().statusCode(201);
    JsonNode preferenceBody = json(preference);
    assertResponse("UserPreference", preferenceBody);
    UUID preferenceId = UUID.fromString(preferenceBody.path("preferenceId").asText());

    Response ownerList = requestAs(USER_A).get("/api/v1/users/{userId}/preferences", USER_A);
    ownerList.then().statusCode(200);
    assertResponse("UserPreferenceList", json(ownerList));
    assertError(
        requestAs(USER_B).get("/api/v1/users/{userId}/preferences", USER_A),
        404,
        "RESOURCE_NOT_FOUND");

    assertThat(preferenceRepository.findByUserIdOrderByUpdatedAtDesc(USER_A))
        .anySatisfy(item -> assertThat(item.getSource().name()).isEqualTo("INFERRED"));
    request()
        .header("Idempotency-Key", key("preference-delete"))
        .delete("/api/v1/users/{userId}/preferences/{preferenceId}", USER_A, preferenceId)
        .then()
        .statusCode(204);
    assertThat(preferenceRepository.findByPreferenceIdAndUserId(preferenceId, USER_A)).isEmpty();
  }

  private Response createTrip(UUID userId, String title, String timeZone) throws Exception {
    return postJson(
        "/api/v1/trips",
        Map.of("userId", userId, "title", title, "timeZone", timeZone),
        key("trip"));
  }

  private Response saveRequirement(UUID tripId, Map<String, Object> body) throws Exception {
    return request()
        .contentType(ContentType.JSON)
        .header("Idempotency-Key", key("requirement"))
        .body(objectMapper.writeValueAsBytes(body))
        .put("/api/v1/trips/{tripId}/requirements", tripId);
  }

  private Response postJson(String path, Object body, String idempotencyKey) throws Exception {
    return request()
        .contentType(ContentType.JSON)
        .header("Idempotency-Key", idempotencyKey)
        .body(objectMapper.writeValueAsBytes(body))
        .post(path);
  }

  private Response upload(UUID tripId, String fileName, String mediaType, byte[] content) {
    return request()
        .header("Idempotency-Key", key("document"))
        .multiPart("tripId", tripId.toString())
        .multiPart("file", fileName, content, mediaType)
        .post("/api/v1/documents/parse");
  }

  private RequestSpecification request() {
    return requestAs(USER_A);
  }

  private RequestSpecification requestAs(UUID user) {
    return given()
        .baseUri("http://127.0.0.1")
        .port(port)
        .header("Authorization", "Bearer " + TestJwts.userToken(user));
  }

  private RequestSpecification requestAdmin() {
    return given()
        .baseUri("http://127.0.0.1")
        .port(port)
        .header("Authorization", "Bearer " + TestJwts.adminToken(USER_A));
  }

  private JsonNode json(Response response) throws Exception {
    return objectMapper.readTree(response.asByteArray());
  }

  private void assertError(Response response, int status, String code) throws Exception {
    response.then().statusCode(status).contentType(ContentType.JSON);
    assertErrorEnvelope(json(response), code);
  }

  private List<JsonNode> sseData(String body) throws Exception {
    List<JsonNode> events = new ArrayList<>();
    for (String line : body.lines().toList()) {
      if (line.startsWith("data:")) {
        events.add(objectMapper.readTree(line.substring("data:".length())));
      }
    }
    return List.copyOf(events);
  }

  private static Map<String, Object> requirement(String budget, String currency) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("origin", Map.of("name", "上海"));
    request.put("destinations", List.of(Map.of("name", "北京")));
    request.put("startDate", "2026-10-01");
    request.put("endDate", "2026-10-03");
    request.put(
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
                List.of())));
    request.put("totalBudget", new BigDecimal(budget));
    request.put("currency", currency);
    request.put("interests", List.of("历史"));
    request.put("pace", "BALANCED");
    request.put("mustVisit", List.of("故宫"));
    request.put("avoid", List.of());
    request.put("freeTextConstraints", "每天九点后出发");
    return request;
  }

  private static Path fixture(String fileName) {
    Path root = Path.of("").toAbsolutePath();
    while (root != null) {
      Path candidate = root.resolve("qa/fixtures/documents/" + fileName);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      root = root.getParent();
    }
    throw new IllegalStateException("Could not locate fixture " + fileName);
  }

  private static String key(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
