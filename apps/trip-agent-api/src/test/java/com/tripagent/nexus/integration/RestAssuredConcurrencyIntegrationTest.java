package com.tripagent.nexus.integration;

import static io.restassured.RestAssured.given;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.RefreshMode.BEFORE_EACH_TEST_METHOD;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.application.idempotency.IdempotencyService;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelPlanEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelPlanRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TripRepository;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(
    provider = EMBEDDED,
    type = POSTGRES,
    refresh = BEFORE_EACH_TEST_METHOD)
class RestAssuredConcurrencyIntegrationTest {

  @LocalServerPort private int port;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private IdempotencyService idempotencyService;
  @Autowired private TripRepository tripRepository;
  @Autowired private TravelPlanRepository planRepository;

  @Test
  void concurrentIdempotentWritesCreateExactlyOneTrip() throws Exception {
    byte[] payload = syntheticTripPayload();
    int concurrency = 20;
    CountDownLatch ready = new CountDownLatch(concurrency);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(concurrency)) {
      List<CompletableFuture<Response>> requests =
          java.util.stream.IntStream.range(0, concurrency)
              .mapToObj(
                  ignored ->
                      CompletableFuture.supplyAsync(
                          () -> {
                            ready.countDown();
                            await(start);
                            return given()
                                .baseUri("http://127.0.0.1")
                                .port(port)
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", "qa-concurrent-trip-0001")
                                .body(payload)
                                .post("/api/v1/trips");
                          },
                          executor))
              .toList();
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<Response> responses = requests.stream().map(CompletableFuture::join).toList();
      assertThat(responses).allSatisfy(response -> response.then().statusCode(201));
      Set<String> tripIds =
          responses.stream()
              .map(response -> response.jsonPath().getString("tripId"))
              .collect(java.util.stream.Collectors.toSet());
      assertThat(tripIds).hasSize(1).doesNotContainNull();
      assertThat(tripRepository.count()).isEqualTo(1);
    }
  }

  @Test
  void slowIdempotentCommandDoesNotExhaustConnectionsForWaiters() throws Exception {
    int concurrency = 20;
    AtomicInteger commandExecutions = new AtomicInteger();
    CountDownLatch ready = new CountDownLatch(concurrency);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(concurrency)) {
      List<CompletableFuture<String>> requests =
          java.util.stream.IntStream.range(0, concurrency)
              .mapToObj(
                  ignored ->
                      CompletableFuture.supplyAsync(
                          () -> {
                            ready.countDown();
                            await(start);
                            return idempotencyService.execute(
                                "qa-slow-concurrent-key-0001",
                                "TEST:/idempotency/slow",
                                Map.of("payload", "synthetic"),
                                String.class,
                                () -> {
                                  commandExecutions.incrementAndGet();
                                  pause(Duration.ofSeconds(4));
                                  return "stable-response";
                                });
                          },
                          executor))
              .toList();
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(requests.stream().map(CompletableFuture::join))
          .containsOnly("stable-response")
          .hasSize(concurrency);
      assertThat(commandExecutions).hasValue(1);
    }
  }

  @Test
  void malformedRequestReturnsTraceableButSanitizedProblem() {
    Response response =
        given()
            .baseUri("http://127.0.0.1")
            .port(port)
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", "qa-invalid-trip-0001")
            .body("{\"userId\":\"not-a-uuid\",\"title\":\"\"}")
            .post("/api/v1/trips");

    response.then().statusCode(400).contentType(ContentType.JSON);
    assertThat(response.jsonPath().getString("traceId")).matches("[0-9a-f]{32}");
    String body = response.asString();
    assertThat(body)
        .doesNotContain("Exception", "stackTrace", "jdbc:", "password", "Authorization");
    assertThat(response.jsonPath().getString("code")).isNotBlank();
  }

  @Test
  void concurrentConfirmationAllowsOneWinnerAndRejectsStalePlanVersions() throws Exception {
    JsonNode fixture = syntheticTripFixture();
    Response tripResponse =
        given()
            .baseUri("http://127.0.0.1")
            .port(port)
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", "qa-plan-race-trip-0001")
            .body(objectMapper.writeValueAsBytes(fixture.path("trip")))
            .post("/api/v1/trips");
    tripResponse.then().statusCode(201);
    UUID tripId = UUID.fromString(tripResponse.jsonPath().getString("tripId"));

    given()
        .baseUri("http://127.0.0.1")
        .port(port)
        .contentType(ContentType.JSON)
        .header("Idempotency-Key", "qa-plan-race-requirement-0001")
        .body(objectMapper.writeValueAsBytes(fixture.path("requirement")))
        .put("/api/v1/trips/{tripId}/requirements", tripId)
        .then()
        .statusCode(200);

    Response accepted =
        given()
            .baseUri("http://127.0.0.1")
            .port(port)
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", "qa-plan-race-create-0001")
            .body(objectMapper.writeValueAsBytes(java.util.Map.of("tripId", tripId)))
            .post("/api/v1/plans");
    accepted.then().statusCode(202);
    UUID planId = UUID.fromString(accepted.jsonPath().getString("resourceId"));
    TravelPlanEntity plan = planRepository.findById(planId).orElseThrow();
    plan.markReady("Synthetic ready plan", Instant.parse("2026-09-10T00:00:00Z"));
    planRepository.saveAndFlush(plan);

    int concurrency = 20;
    CountDownLatch ready = new CountDownLatch(concurrency);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(concurrency)) {
      List<CompletableFuture<Response>> requests =
          java.util.stream.IntStream.range(0, concurrency)
              .mapToObj(
                  index ->
                      CompletableFuture.supplyAsync(
                          () -> {
                            ready.countDown();
                            await(start);
                            return given()
                                .baseUri("http://127.0.0.1")
                                .port(port)
                                .contentType(ContentType.JSON)
                                .header(
                                    "Idempotency-Key", "qa-plan-race-confirm-%04d".formatted(index))
                                .body("{\"expectedVersion\":1}")
                                .post("/api/v1/plans/{planId}/confirm", planId);
                          },
                          executor))
              .toList();
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<Response> responses = requests.stream().map(CompletableFuture::join).toList();
      assertThat(responses).filteredOn(response -> response.statusCode() == 200).hasSize(1);
      assertThat(responses)
          .filteredOn(response -> response.statusCode() == 409)
          .hasSize(concurrency - 1);
      assertThat(responses)
          .allSatisfy(response -> assertThat(response.statusCode()).isIn(200, 409));
    }
  }

  private byte[] syntheticTripPayload() throws Exception {
    return objectMapper.writeValueAsBytes(syntheticTripFixture().path("trip"));
  }

  private JsonNode syntheticTripFixture() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.exists(root.resolve("qa/fixtures"))) {
      root = root.getParent();
    }
    if (root == null) {
      throw new IllegalStateException("Could not locate synthetic QA fixtures");
    }
    return objectMapper.readTree(root.resolve("qa/fixtures/trips/beijing-three-day.json").toFile());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent test start timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent test was interrupted", exception);
    }
  }

  private static void pause(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent test was interrupted", exception);
    }
  }
}
