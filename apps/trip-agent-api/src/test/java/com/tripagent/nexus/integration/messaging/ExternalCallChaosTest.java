package com.tripagent.nexus.integration.messaging;

import static com.tripagent.nexus.testsupport.ChaosEvidence.duration;
import static com.tripagent.nexus.testsupport.ChaosEvidence.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;

import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ExternalCallChaosTest {

  @Test
  void callerTimeoutMustCountAsCircuitFailureEvenWhenWorkerReturnsAfterCancellation() {
    AsyncGovernanceProperties properties = new AsyncGovernanceProperties();
    properties.setExternalCallTimeout(duration("callTimeoutMs"));
    ExternalCallExecutor executor = new ExternalCallExecutor(properties);
    AtomicInteger invocations = new AtomicInteger();
    AtomicInteger completed = new AtomicInteger();
    try {
      for (int attempt = 0; attempt < properties.getExternalCircuitMinimumCalls(); attempt++) {
        var failure =
            catchThrowableOfType(
                () ->
                    executor.execute(
                        "cancelled-worker",
                        1,
                        () -> {
                          invocations.incrementAndGet();
                          try {
                            new CountDownLatch(1).await();
                          } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                          } finally {
                            completed.incrementAndGet();
                          }
                          return "late-response";
                        }),
                AsyncOperationException.class);
        assertThat(failure.code()).isEqualTo("EXTERNAL_CALL_TIMEOUT");
        int expected = attempt + 1;
        await().atMost(duration("assertionTimeoutMs")).until(() -> completed.get() == expected);
      }
      var rejected =
          catchThrowableOfType(
              () ->
                  executor.execute(
                      "cancelled-worker",
                      1,
                      () -> {
                        invocations.incrementAndGet();
                        return "must-not-run";
                      }),
              AsyncOperationException.class);
      assertThat(rejected).as("timeouts must open the circuit").isNotNull();
      assertThat(invocations).hasValue(5);
    } finally {
      executor.close();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void circuitOpensRejectsWithoutRetryAndClosesAfterHalfOpenProbe() {
    var properties = new AsyncGovernanceProperties();
    properties.setExternalCircuitWindow(value("circuitWindow"));
    properties.setExternalCircuitMinimumCalls(value("circuitMinimumCalls"));
    properties.setExternalCircuitOpenDuration(duration("circuitOpenMs"));
    properties.setExternalCircuitHalfOpenCalls(value("circuitHalfOpenCalls"));
    var executor = new ExternalCallExecutor(properties);
    var attempts = new AtomicInteger();
    try {
      for (int i = 0; i < value("circuitMinimumCalls"); i++) {
        var failure =
            catchThrowableOfType(
                () ->
                    executor.execute(
                        "circuit",
                        1,
                        () -> {
                          attempts.incrementAndGet();
                          throw new AsyncOperationException(
                              "SYNTHETIC_UNAVAILABLE", true, "Service unavailable.");
                        }),
                AsyncOperationException.class);
        assertThat(failure.code()).isEqualTo("SYNTHETIC_UNAVAILABLE");
      }
      var breakers =
          (Map<String, CircuitBreaker>) ReflectionTestUtils.getField(executor, "circuitBreakers");
      var breaker = breakers.get("circuit");
      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
      var transitions = new java.util.concurrent.CopyOnWriteArrayList<String>();
      breaker
          .getEventPublisher()
          .onStateTransition(event -> transitions.add(event.getStateTransition().name()));
      var rejected =
          catchThrowableOfType(
              () ->
                  executor.execute(
                      "circuit",
                      value("maxAttempts"),
                      () -> {
                        attempts.incrementAndGet();
                        return "must-not-run";
                      }),
              AsyncOperationException.class);
      assertThat(rejected.code()).isEqualTo("EXTERNAL_CIRCUIT_OPEN");
      assertThat(attempts).hasValue(value("circuitMinimumCalls"));
      await()
          .atMost(duration("recoveryTimeoutMs"))
          .ignoreExceptions()
          .untilAsserted(
              () ->
                  assertThat(executor.execute("circuit", 1, () -> "recovered"))
                      .isEqualTo("recovered"));
      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      assertThat(transitions).contains("OPEN_TO_HALF_OPEN", "HALF_OPEN_TO_CLOSED");
    } finally {
      executor.close();
    }
  }

  @Test
  void timedOutUncooperativeWorkerRetainsBulkheadCapacityUntilItExits() throws Exception {
    var properties = new AsyncGovernanceProperties();
    properties.setExternalCallTimeout(duration("callTimeoutMs"));
    properties.setExternalMaxConcurrency(1);
    var executor = new ExternalCallExecutor(properties);
    var release = new CountDownLatch(1);
    var exited = new CountDownLatch(1);
    try {
      var timeout =
          catchThrowableOfType(
              () ->
                  executor.execute(
                      "capacity",
                      1,
                      () -> {
                        try {
                          while (release.getCount() > 0) {
                            try {
                              release.await();
                            } catch (InterruptedException ignored) {
                              // Model a provider that ignores interruption until its own I/O
                              // returns.
                            }
                          }
                          return "late";
                        } finally {
                          exited.countDown();
                        }
                      }),
              AsyncOperationException.class);
      assertThat(timeout.code()).isEqualTo("EXTERNAL_CALL_TIMEOUT");
      var rejected =
          catchThrowableOfType(
              () -> executor.execute("capacity", value("maxAttempts"), () -> "must-not-run"),
              AsyncOperationException.class);
      assertThat(rejected.code()).isEqualTo("EXTERNAL_CAPACITY_EXHAUSTED");
      release.countDown();
      assertThat(
              exited.await(value("assertionTimeoutMs"), java.util.concurrent.TimeUnit.MILLISECONDS))
          .isTrue();
      await()
          .atMost(duration("assertionTimeoutMs"))
          .ignoreExceptions()
          .untilAsserted(
              () ->
                  assertThat(executor.execute("capacity", 1, () -> "recovered"))
                      .isEqualTo("recovered"));
    } finally {
      release.countDown();
      executor.close();
    }
  }
}
