package com.tripagent.nexus.integration;

import static com.tripagent.nexus.testsupport.ChaosEvidence.duration;
import static com.tripagent.nexus.testsupport.ChaosEvidence.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.testsupport.ChaosEvidence;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class Stage7ProcessRecoveryTestcontainersIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka-native:3.9.1")
          .withStartupTimeout(duration("containerStartupTimeoutMs"));

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(duration("assertionTimeoutMs")).build();

  // A constrained Windows + Docker Desktop (WSL2) host starts a forked JVM materially slower than
  // the Linux/macOS CI hardware the chaos thresholds are calibrated for. Wall-clock waits and the
  // recovery SLA are therefore scaled up on Windows; the recovery *correctness* assertions (no
  // lost tasks, no duplicate work, no replayed committed steps, checkpoints cleared) are
  // identical on every platform.
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
  private static final long WALL_CLOCK_SCALE = WINDOWS ? 4L : 1L;

  private static Duration startupTimeout() {
    return duration("applicationStartupTimeoutMs").multipliedBy(WINDOWS ? 2L : 1L);
  }

  private static Duration recoveryTimeout() {
    return duration("recoveryTimeoutMs").multipliedBy(WALL_CLOCK_SCALE);
  }

  private Process application;
  private int port;
  private Path logs;
  private int boot;

  @ParameterizedTest
  @ValueSource(strings = {"ResearchAgent", "AccommodationAgent"})
  void runningAgentResumesAfterKillWithoutRepeatingCommittedSteps(String blocked) throws Exception {
    try (var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    logs =
        ChaosEvidence.outputDirectory()
            .getParent()
            .resolve("logs/agent-resume-" + blocked + "-" + UUID.randomUUID());
    Files.createDirectories(logs);
    Path audit = logs.resolve("model-calls");
    try {
      startApplication(blocked, audit);
      JsonNode accepted = submitPlan();
      String taskId = accepted.path("taskId").asText();
      var completedNames =
          blocked.equals("ResearchAgent")
              ? List.of("RequirementAgent")
              : List.of(
                  "RequirementAgent", "ResearchAgent", "TransportAgent", "RecommendationAgent");
      await()
          .atMost(recoveryTimeout())
          .untilAsserted(
              () -> {
                assertThat(audit.resolve("blocked")).exists();
                for (String name : completedNames) {
                  assertThat(
                          count(
                              "SELECT count(*) FROM agent_result WHERE task_id='"
                                  + taskId
                                  + "' AND agent_type='"
                                  + name
                                  + "' AND status IN ('SUCCEEDED','PARTIAL_SUCCESS')"))
                      .isEqualTo(1);
                }
              });
      assertThat(request("GET", "/api/v1/tasks/" + taskId, null).path("stage").asText())
          .isEqualTo("AGENTS_RUNNING");
      assertThat(count("SELECT recovery_attempts FROM task_record WHERE task_id='" + taskId + "'"))
          .isZero();
      var callsBefore = new java.util.LinkedHashMap<String, Long>();
      for (String name : completedNames) {
        try (var lines = Files.lines(audit.resolve(name + ".calls"))) {
          callsBefore.put(name, lines.count());
        }
      }
      ChaosEvidence.run(
          "a2a.resume." + blocked,
          "PROCESS_KILL_DURING_AGENT",
          "Real Coordinator with PostgreSQL checkpoints; Mock model blocked inside selected running Agent",
          record -> {
            record.trace(
                accepted.path("traceId").asText(),
                "Persisted planning task and checkpoint correlation");
            record.measure("taskId", taskId);
            record.measure("applicationLogs", logs.toString());
            record.measure("modelCallsBeforeKill", callsBefore);
            record.injected();
            application.destroyForcibly();
            assertThat(application.waitFor(value("assertionTimeoutMs"), TimeUnit.MILLISECONDS))
                .isTrue();
            record.detected();
            record.degraded(
                "Planning paused during a running Agent; completed results remain persisted.");
            record.cleared();
            long started = System.nanoTime();
            startApplication("", audit);
            var result = awaitTask(taskId);
            assertThat(result.path("status").asText()).isEqualTo("PARTIAL_SUCCESS");
            assertThat(result.path("traceId").asText())
                .isEqualTo(accepted.path("traceId").asText());
            assertThat(result.path("resultResourceIds")).hasSize(2);
            var callsAfter = new java.util.LinkedHashMap<String, Long>();
            for (String name : completedNames) {
              long calls;
              try (var lines = Files.lines(audit.resolve(name + ".calls"))) {
                calls = lines.count();
              }
              callsAfter.put(name, calls);
              assertThat(calls).isEqualTo(callsBefore.get(name));
              assertThat(
                      count(
                          "SELECT count(*) FROM agent_result WHERE task_id='"
                              + taskId
                              + "' AND agent_type='"
                              + name
                              + "'"))
                  .isEqualTo(1);
            }
            assertThat(
                    count(
                        "SELECT count(*) FROM agent_result WHERE task_id='"
                            + taskId
                            + "' AND agent_type='"
                            + blocked
                            + "'"))
                .isEqualTo(2);
            assertThat(
                    count(
                        "SELECT count(*) FROM agent_result WHERE task_id='"
                            + taskId
                            + "' AND status='RUNNING'"))
                .isZero();
            assertThat(
                    count(
                        "SELECT count(*) FROM planning_checkpoint WHERE task_id='" + taskId + "'"))
                .isZero();
            assertThat(
                    count(
                        "SELECT count(*) FROM event_inbox WHERE task_id='"
                            + taskId
                            + "' AND status='PROCESSING'"))
                .isZero();
            assertThat(
                    count(
                        "SELECT count(*) FROM (SELECT progress,lag(progress) OVER (ORDER BY sequence) previous"
                            + " FROM task_event WHERE task_id='"
                            + taskId
                            + "') e WHERE progress < previous"))
                .isZero();
            for (var planId : result.path("resultResourceIds")) {
              assertThat(
                      request("GET", "/api/v1/plans/" + planId.asText(), null)
                          .path("status")
                          .asText())
                  .isIn("READY", "PARTIAL");
            }
            assertThat(
                    count(
                        "SELECT count(*) FROM (SELECT plan_family_id,version FROM travel_plan GROUP BY plan_family_id,version HAVING count(*)>1) d"))
                .isZero();
            long recoveredMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertThat(recoveredMs).isLessThan(recoveryTimeout().toMillis());
            record.measure("recoveryMilliseconds", recoveredMs);
            record.measure("modelCallsAfterResume", callsAfter);
            record.measure("interruptedAgentAttempts", 2);
            record.tasks(UUID.fromString(taskId), accepted.path("traceId").asText(), 0, 0);
            record.recovered();
          });
    } finally {
      if (application != null && application.isAlive()) {
        application.destroy();
        if (!application.waitFor(value("assertionTimeoutMs"), TimeUnit.MILLISECONDS)) {
          application.destroyForcibly();
          application.waitFor();
        }
      }
    }
  }

  @Test
  void processKillDuringBrokerOutageResumesQueuedPlansWithoutRepeatingCompletedWork()
      throws Exception {
    try (var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    logs =
        ChaosEvidence.outputDirectory()
            .getParent()
            .resolve("logs/process-recovery-" + UUID.randomUUID());
    Files.createDirectories(logs);
    try {
      startApplication();
      JsonNode baseline = submitPlan();
      JsonNode completed = awaitTask(baseline.path("taskId").asText());
      assertThat(completed.path("status").asText()).isIn("SUCCEEDED", "PARTIAL_SUCCESS");
      long baselineSteps =
          count(
              "SELECT count(*) FROM agent_result WHERE task_id = '"
                  + baseline.path("taskId").asText()
                  + "'");
      long baselinePlans = count("SELECT count(*) FROM travel_plan");
      assertThat(baselineSteps).isGreaterThan(0);
      ChaosEvidence.run(
          "a2a.processRestart",
          "PROCESS_KILL_AND_BROKER_PAUSE",
          "Real Spring Boot process including Coordinator, Kafka, PostgreSQL and Redis; queued work recovery",
          record -> {
            record.measure("applicationLogs", logs.toString());
            record.trace(
                baseline.path("traceId").asText(),
                "Persisted baseline task and HTTP task responses");
            record.injected();
            KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
            var queued = new ArrayList<JsonNode>();
            try {
              for (int i = 0; i < value("businessBacklogTasks"); i++) queued.add(submitPlan());
              record.measure(
                  "queuedTaskIds",
                  queued.stream().map(task -> task.path("taskId").asText()).toList());
              record.measure(
                  "queuedTraceIds",
                  queued.stream().map(task -> task.path("traceId").asText()).toList());
              for (var task : queued) {
                assertThat(
                        request("GET", "/api/v1/tasks/" + task.path("taskId").asText(), null)
                            .path("status")
                            .asText())
                    .isEqualTo("QUEUED");
              }
              assertThat(count("SELECT count(*) FROM event_outbox WHERE status = 'PENDING'"))
                  .isGreaterThanOrEqualTo(queued.size());
              record.degraded(
                  "Accepted planning tasks remain queued while messaging is unavailable.");
              application.destroyForcibly();
              assertThat(application.waitFor(value("assertionTimeoutMs"), TimeUnit.MILLISECONDS))
                  .isTrue();
              assertThat(application.isAlive()).isFalse();
              record.measure("killedProcessId", application.pid());
            } finally {
              KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
              record.cleared();
            }
            startApplication();
            for (var task : queued) {
              var result = awaitTask(task.path("taskId").asText());
              assertThat(result.path("status").asText()).isIn("SUCCEEDED", "PARTIAL_SUCCESS");
              assertThat(result.path("resultResourceIds")).hasSize(2);
              for (var plan : result.path("resultResourceIds")) {
                assertThat(
                        request("GET", "/api/v1/plans/" + plan.asText(), null)
                            .path("status")
                            .asText())
                    .isIn("READY", "PARTIAL");
              }
            }
            assertThat(
                    count(
                        "SELECT count(*) FROM agent_result WHERE task_id = '"
                            + baseline.path("taskId").asText()
                            + "'"))
                .isEqualTo(baselineSteps);
            assertThat(
                    request("GET", "/api/v1/tasks/" + baseline.path("taskId").asText(), null)
                        .path("resultResourceIds"))
                .isEqualTo(completed.path("resultResourceIds"));
            assertThat(
                    count(
                        "SELECT count(*) FROM (SELECT plan_family_id,version FROM travel_plan GROUP BY plan_family_id,version HAVING count(*) > 1) duplicates"))
                .isZero();
            assertThat(count("SELECT count(*) FROM travel_plan")).isGreaterThan(baselinePlans);
            await()
                .atMost(recoveryTimeout())
                .untilAsserted(
                    () ->
                        assertThat(
                                count("SELECT count(*) FROM event_outbox WHERE status = 'PENDING'"))
                            .isZero());
            record.measure("acceptedQueuedTasks", queued.size());
            record.measure("completedQueuedTasks", queued.size());
            record.measure("baselineAgentExecutionsBefore", baselineSteps);
            record.measure("baselineAgentExecutionsAfter", baselineSteps);
            record.measure("finalPendingOutbox", 0);
            record.tasks(
                UUID.fromString(baseline.path("taskId").asText()),
                baseline.path("traceId").asText(),
                0,
                0);
            record.measure(
                "queuedTaskIds",
                queued.stream().map(task -> task.path("taskId").asText()).toList());
            record.measure(
                "queuedTraceIds",
                queued.stream().map(task -> task.path("traceId").asText()).toList());
            record.recovered();
          });
    } finally {
      if (application != null && application.isAlive()) {
        application.destroy();
        if (!application.waitFor(value("assertionTimeoutMs"), TimeUnit.MILLISECONDS)) {
          application.destroyForcibly();
          application.waitFor();
        }
      }
    }
  }

  private void startApplication() throws Exception {
    startApplication(null, null);
  }

  private void startApplication(String blockedAgent, Path audit) throws Exception {
    String mainClass =
        blockedAgent == null
            ? "com.tripagent.nexus.TripAgentApiApplication"
            : "com.tripagent.nexus.testsupport.ResumeBarrierApplication";
    var jvmArgs =
        new ArrayList<String>(
            List.of(
                "-Xmx768m",
                "-XX:TieredStopAtLevel=1",
                "-XX:+UseSerialGC",
                "-Dfile.encoding=UTF-8",
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                mainClass,
                "--spring.profiles.active=test,mock-model",
                "--server.port=" + port,
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.data.redis.password=",
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.kafka.producer.properties.max.block.ms=2000",
                "--spring.kafka.producer.properties.request.timeout.ms=2000",
                "--spring.kafka.producer.properties.delivery.timeout.ms=4000",
                "--app.agents.enabled=true",
                "--app.planning.enabled=true",
                "--app.planning.provider-mode=MOCK",
                "--app.mcp.provider-mode=MOCK",
                "--app.mcp.base-url=http://127.0.0.1:" + port,
                "--app.async-governance.enabled=true",
                "--app.async-governance.redis-enabled=true",
                "--app.async-governance.stale-task-threshold="
                    + (blockedAgent == null ? "1h" : "2s"),
                "--app.async-governance.recovery-poll-interval=500ms",
                "--app.async-governance.publish-timeout=1s",
                "--app.async-governance.initial-backoff=100ms",
                "--app.async-governance.maximum-backoff=1s",
                "--app.async-governance.max-attempts=20",
                "--app.async-governance.outbox-poll-interval=200ms",
                "--app.health.kafka.enabled=false",
                "--app.health.ai-retrieval.enabled=false",
                "--logging.level.org.apache.kafka.clients.consumer.internals.ConsumerCoordinator=INFO",
                "--springdoc.api-docs.enabled=false",
                "--management.tracing.enabled=false"));
    if (blockedAgent != null) {
      jvmArgs.add("--qa.resume.block-agent=" + blockedAgent);
      jvmArgs.add("--qa.resume.audit-directory=" + audit.toAbsolutePath());
    }
    String javaBinary = Path.of(System.getProperty("java.home"), "bin/java").toString();
    List<String> command;
    if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
      // Windows CreateProcess limits the command line to ~32K characters and the surefire test
      // classpath easily exceeds it (CreateProcess error=206). Pass every JVM argument through a
      // @argfile, which has no length limit, leaving only the short "java @argfile" command.
      //
      // The java launcher decodes @argfile contents using the JNU (native) charset rather than
      // UTF-8; on a Chinese Windows that is GBK, so a UTF-8 encoded classpath with non-ASCII
      // directory names (e.g. 王公子) parses but fails to resolve. Encode the file with the same
      // JNU charset the launcher uses.
      Path argfile = logs.resolve("app-" + (boot + 1) + ".args");
      List<String> lines = new ArrayList<>();
      for (String arg : jvmArgs) {
        lines.add(
            arg.matches(".*\\s.*")
                ? "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                : arg);
      }
      Files.write(argfile, lines, argfileCharset());
      command = List.of(javaBinary, "@" + argfile.toAbsolutePath());
    } else {
      command = new ArrayList<>();
      command.add(javaBinary);
      command.addAll(jvmArgs);
    }
    var builder =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(logs.resolve("boot-" + (++boot) + ".log").toFile());
    builder.environment().put("DEEPSEEK_API_KEY", "");
    builder.environment().put("REAL_MODEL_TEST_ENABLED", "false");
    application = builder.start();
    await()
        .atMost(startupTimeout())
        .failFast("Application process exited before readiness", () -> !application.isAlive())
        .pollInterval(Duration.ofMillis(100))
        .ignoreExceptions()
        .untilAsserted(
            () -> {
              assertThat(application.isAlive()).as("Application log: %s", logs).isTrue();
              assertThat(request("GET", "/actuator/health/readiness", null).path("status").asText())
                  .isEqualTo("UP");
            });
  }

  private JsonNode submitPlan() throws Exception {
    JsonNode fixture =
        mapper.readTree(Path.of("../../qa/fixtures/trips/beijing-three-day.json").toFile());
    JsonNode trip = request("POST", "/api/v1/trips", fixture.path("trip"));
    request(
        "PUT",
        "/api/v1/trips/" + trip.path("tripId").asText() + "/requirements",
        fixture.path("requirement"));
    return request(
        "POST",
        "/api/v1/plans",
        mapper.valueToTree(
            Map.of("tripId", trip.path("tripId").asText(), "title", "Synthetic recovery plan")));
  }

  private JsonNode awaitTask(String taskId) {
    final JsonNode[] result = new JsonNode[1];
    await()
        .atMost(recoveryTimeout())
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> {
              result[0] = request("GET", "/api/v1/tasks/" + taskId, null);
              assertThat(result[0].path("status").asText())
                  .isIn("SUCCEEDED", "PARTIAL_SUCCESS", "FAILED");
            });
    return result[0];
  }

  private JsonNode request(String method, String path, JsonNode body) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(duration("assertionTimeoutMs"))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", "stage7-" + UUID.randomUUID());
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body.toString()));
    var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("HTTP %s %s", method, path).isBetween(200, 299);
    return mapper.readTree(response.body());
  }

  /** Charset the {@code java} launcher uses when decoding {@code @argfile} contents. */
  private static java.nio.charset.Charset argfileCharset() {
    try {
      return java.nio.charset.Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8"));
    } catch (Exception ignored) {
      return java.nio.charset.StandardCharsets.UTF_8;
    }
  }

  private long count(String sql) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var result = statement.executeQuery(sql)) {
        result.next();
        return result.getLong(1);
      }
    }
  }
}
