package com.tripagent.nexus.integration.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModelCallExecutorTest {

  private ModelCallExecutor executor;

  @AfterEach
  void closeExecutor() {
    if (executor != null) {
      executor.close();
    }
  }

  @Test
  void returnsSuccessfulResultWithoutRetry() {
    List<Duration> backoffs = new ArrayList<>();
    executor = executor(properties(3, Duration.ofSeconds(1), 2), backoffs);

    String result = executor.execute(() -> "ok");

    assertThat(result).isEqualTo("ok");
    assertThat(backoffs).isEmpty();
  }

  @Test
  void retriesRetryableFailuresWithExponentialBackoff() {
    List<Duration> backoffs = new ArrayList<>();
    AtomicInteger calls = new AtomicInteger();
    executor = executor(properties(3, Duration.ofSeconds(1), 2), backoffs);

    String result =
        executor.execute(
            () -> {
              if (calls.incrementAndGet() < 3) {
                throw new ModelGatewayException("MODEL_RATE_LIMITED", true, "Rate limited.");
              }
              return "recovered";
            });

    assertThat(result).isEqualTo("recovered");
    assertThat(calls).hasValue(3);
    assertThat(backoffs).containsExactly(Duration.ofMillis(10), Duration.ofMillis(20));
  }

  @Test
  void classifiesTimedOutCallsAsRetryable() {
    executor = executor(properties(1, Duration.ofMillis(20), 1), new ArrayList<>());

    assertThatThrownBy(
            () ->
                executor.execute(
                    () -> {
                      try {
                        Thread.sleep(Duration.ofSeconds(1));
                      } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                      }
                      return "late";
                    }))
        .isInstanceOfSatisfying(
            ModelGatewayException.class,
            exception -> {
              assertThat(exception.getCode()).isEqualTo("MODEL_TIMEOUT");
              assertThat(exception.isRetryable()).isTrue();
            });
  }

  @Test
  void rejectsCallsBeyondTheConcurrencyLimit() throws Exception {
    ModelGatewayProperties properties = properties(1, Duration.ofSeconds(2), 1);
    properties.setConcurrencyWait(Duration.ofMillis(10));
    executor = executor(properties, new ArrayList<>());
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService caller = Executors.newSingleThreadExecutor();
    try {
      caller.submit(
          () ->
              executor.execute(
                  () -> {
                    entered.countDown();
                    try {
                      release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                      Thread.currentThread().interrupt();
                    }
                    return "first";
                  }));
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> executor.execute(() -> "second"))
          .isInstanceOfSatisfying(
              ModelGatewayException.class,
              exception -> assertThat(exception.getCode()).isEqualTo("MODEL_CONCURRENCY_LIMIT"));
    } finally {
      release.countDown();
      caller.shutdownNow();
    }
  }

  @Test
  void rejectsCallsAfterTheConfiguredTotalProviderCallBudget() {
    ModelGatewayProperties properties = properties(1, Duration.ofSeconds(1), 2);
    properties.setMaxTotalCalls(2);
    executor = executor(properties, new ArrayList<>());

    assertThat(executor.execute(() -> "first")).isEqualTo("first");
    assertThat(executor.execute(() -> "second")).isEqualTo("second");

    assertThatThrownBy(() -> executor.execute(() -> "third"))
        .isInstanceOfSatisfying(
            ModelGatewayException.class,
            exception -> {
              assertThat(exception.getCode()).isEqualTo("MODEL_CALL_BUDGET_EXHAUSTED");
              assertThat(exception.isRetryable()).isFalse();
            });
  }

  @Test
  void countsEveryRetryAgainstTheProviderCallBudget() {
    ModelGatewayProperties properties = properties(3, Duration.ofSeconds(1), 2);
    properties.setMaxTotalCalls(2);
    AtomicInteger providerCalls = new AtomicInteger();
    executor = executor(properties, new ArrayList<>());

    assertThatThrownBy(
            () ->
                executor.execute(
                    () -> {
                      providerCalls.incrementAndGet();
                      throw new ModelGatewayException("MODEL_RATE_LIMITED", true, "Rate limited.");
                    }))
        .isInstanceOfSatisfying(
            ModelGatewayException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("MODEL_CALL_BUDGET_EXHAUSTED"));
    assertThat(providerCalls).hasValue(2);
  }

  @Test
  void timeoutCannotReleaseCapacityBeforeProviderActuallyStops() {
    var policy =
        properties(1, com.tripagent.nexus.testsupport.ChaosEvidence.duration("callTimeoutMs"), 1);
    executor = executor(policy, new ArrayList<>());
    var release = new CountDownLatch(1);
    try {
      assertThatThrownBy(
              () ->
                  executor.execute(
                      () -> {
                        while (release.getCount() > 0) {
                          try {
                            release.await();
                          } catch (InterruptedException ignored) {
                            // An uncooperative provider remains in flight after the caller's
                            // deadline.
                          }
                        }
                        return "late";
                      }))
          .isInstanceOfSatisfying(
              ModelGatewayException.class,
              error -> assertThat(error.getCode()).isEqualTo("MODEL_TIMEOUT"));
      assertThatThrownBy(() -> executor.execute(() -> "must-not-run"))
          .isInstanceOfSatisfying(
              ModelGatewayException.class,
              error -> assertThat(error.getCode()).isEqualTo("MODEL_CONCURRENCY_LIMIT"));
    } finally {
      release.countDown();
    }
  }

  private static ModelCallExecutor executor(
      ModelGatewayProperties properties, List<Duration> backoffs) {
    return new ModelCallExecutor(
        properties, new ModelExceptionClassifier(), Executors.newFixedThreadPool(4), backoffs::add);
  }

  private static ModelGatewayProperties properties(
      int maxAttempts, Duration timeout, int maxConcurrency) {
    ModelGatewayProperties properties = new ModelGatewayProperties();
    properties.setMaxAttempts(maxAttempts);
    properties.setTimeout(timeout);
    properties.setMaxConcurrency(maxConcurrency);
    properties.setConcurrencyWait(Duration.ofMillis(50));
    properties.setInitialBackoff(Duration.ofMillis(10));
    properties.setBackoffMultiplier(2.0);
    return properties;
  }
}
