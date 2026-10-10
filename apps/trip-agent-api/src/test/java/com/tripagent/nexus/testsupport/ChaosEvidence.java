package com.tripagent.nexus.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ChaosEvidence {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  private static final Path ROOT = repositoryRoot();
  private static final JsonNode CONFIG = configuration();
  private final Map<String, Object> evidence = new LinkedHashMap<>();
  private final Map<String, Object> measurements = new LinkedHashMap<>();

  private ChaosEvidence(String id, String mode, String scope) {
    evidence.put("schemaVersion", 1);
    evidence.put("id", id);
    evidence.put("injectionMode", mode);
    evidence.put("impactScope", scope);
    evidence.put("status", "FAILED");
    evidence.put("injectedAt", null);
    evidence.put("firstDetectedAt", null);
    evidence.put("degradationStartedAt", null);
    evidence.put("faultClearedAt", null);
    evidence.put("recoveredAt", null);
    evidence.put("lostTasks", null);
    evidence.put("duplicateTasks", null);
    evidence.put(
        "taskAccounting", "Not measured: component experiment, no business task submitted");
    evidence.put("traceIds", java.util.List.of());
    evidence.put("taskIds", java.util.List.of());
    evidence.put("traceScope", "No distributed trace emitted by this component experiment");
    evidence.put("userVisibleImpact", "Not observed at the user interface");
    evidence.put("measurements", measurements);
  }

  public static void run(String id, String mode, String scope, Experiment experiment)
      throws Exception {
    ChaosEvidence record = new ChaosEvidence(id, mode, scope);
    try {
      experiment.execute(record);
      if (record.evidence.get("injectedAt") == null
          || record.evidence.get("firstDetectedAt") == null
          || record.evidence.get("recoveredAt") == null) {
        throw new IllegalStateException("CHAOS_TIMELINE_INCOMPLETE");
      }
      record.evidence.put("status", "PASSED");
    } catch (Exception | AssertionError failure) {
      record.evidence.put("failureType", failure.getClass().getSimpleName());
      throw failure;
    } finally {
      record.evidence.put("finishedAt", Instant.now().toString());
      Path directory = outputDirectory();
      Files.createDirectories(directory);
      // Each attempt has its own file, including failed attempts.
      Path output = directory.resolve(id + "-" + UUID.randomUUID() + ".json");
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record.evidence);
    }
  }

  /** Resolves the chaos evidence output directory, honoring the {@code chaos.output} override. */
  public static Path outputDirectory() {
    return Path.of(
        System.getProperty(
            "chaos.output",
            ROOT.resolve("output/test-results/stage7-local/experiments").toString()));
  }

  public void injected() {
    evidence.put("injectedAt", Instant.now().toString());
  }

  public void detected() {
    if (evidence.get("firstDetectedAt") == null) {
      evidence.put("firstDetectedAt", Instant.now().toString());
    }
  }

  public void degraded(String impact) {
    detected();
    evidence.put("degradationStartedAt", Instant.now().toString());
    evidence.put("userVisibleImpact", impact);
  }

  public void cleared() {
    evidence.put("faultClearedAt", Instant.now().toString());
  }

  public void recovered() {
    evidence.put("recoveredAt", Instant.now().toString());
  }

  public void measure(String name, Object value) {
    measurements.put(name, value);
  }

  public void tasks(UUID taskId, String traceId, long lost, long duplicates) {
    evidence.put("taskIds", java.util.List.of(taskId.toString()));
    evidence.put("traceIds", java.util.List.of(traceId));
    evidence.put("traceScope", "Persisted business task correlation");
    evidence.put("lostTasks", lost);
    evidence.put("duplicateTasks", duplicates);
    evidence.put("taskAccounting", "Measured against submitted synthetic business task");
  }

  public void trace(String traceId, String scope) {
    evidence.put("traceIds", java.util.List.of(traceId));
    evidence.put("traceScope", scope);
  }

  public static int value(String name) {
    String envName =
        "QA_CHAOS_"
            + name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
    String override = System.getenv(envName);
    int result =
        override == null
            ? CONFIG.path("thresholds").path(name).asInt(-1)
            : Integer.parseInt(override);
    if (result < 0) {
      throw new IllegalArgumentException("Invalid chaos threshold: " + name);
    }
    return result;
  }

  public static Duration duration(String name) {
    return Duration.ofMillis(value(name));
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null
        && !Files.isRegularFile(current.resolve("qa/config/chaos-stage7.json"))) {
      current = current.getParent();
    }
    if (current == null) {
      throw new IllegalStateException("CHAOS_CONFIGURATION_NOT_FOUND");
    }
    return current;
  }

  private static JsonNode configuration() {
    try {
      return MAPPER.readTree(ROOT.resolve("qa/config/chaos-stage7.json").toFile());
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("CHAOS_CONFIGURATION_INVALID", failure);
    }
  }

  @FunctionalInterface
  public interface Experiment {
    void execute(ChaosEvidence record) throws Exception;
  }
}
