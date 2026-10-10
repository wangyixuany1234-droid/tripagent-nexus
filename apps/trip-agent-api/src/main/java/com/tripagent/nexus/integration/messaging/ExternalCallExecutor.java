package com.tripagent.nexus.integration.messaging;

import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class ExternalCallExecutor {

  private final AsyncGovernanceProperties properties;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
  private final Map<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();

  public ExternalCallExecutor(AsyncGovernanceProperties properties) {
    this.properties = properties;
  }

  public <T> T execute(String name, int maxAttempts, Supplier<T> call) {
    if (maxAttempts < 1 || maxAttempts > properties.getMaxAttempts()) {
      throw new IllegalArgumentException("EXTERNAL_ATTEMPTS_INVALID");
    }
    AsyncOperationException lastFailure = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        return executeOnce(name, call);
      } catch (AsyncOperationException exception) {
        lastFailure = exception;
        if (!exception.retryable()
            || attempt == maxAttempts
            || exception.code().equals("EXTERNAL_CIRCUIT_OPEN")
            || exception.code().equals("EXTERNAL_CAPACITY_EXHAUSTED")
            || Thread.currentThread().isInterrupted()) {
          throw exception;
        }
        sleep(backoff(attempt));
      }
    }
    throw lastFailure == null
        ? new AsyncOperationException(
            "EXTERNAL_CALL_FAILED", true, "A required internal service is unavailable.")
        : lastFailure;
  }

  private <T> T executeOnce(String name, Supplier<T> call) {
    CircuitBreaker circuitBreaker = circuitBreakers.computeIfAbsent(name, this::newCircuitBreaker);
    Bulkhead bulkhead = bulkheads.computeIfAbsent(name, this::newBulkhead);
    try {
      // Count the caller's deadline, not a late worker result. The worker retains its bulkhead
      // slot.
      return circuitBreaker.executeSupplier(
          () -> awaitCall(Bulkhead.decorateSupplier(bulkhead, call)));
    } catch (CallNotPermittedException exception) {
      throw new AsyncOperationException(
          "EXTERNAL_CIRCUIT_OPEN", true, "The service is recovering. Please try again shortly.");
    }
  }

  private <T> T awaitCall(Supplier<T> call) {
    Future<T> future = executor.submit(call::get);
    try {
      return future.get(properties.getExternalCallTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      future.cancel(true);
      throw new AsyncOperationException(
          "EXTERNAL_CALL_TIMEOUT",
          true,
          "A required internal service did not respond before its deadline.",
          exception);
    } catch (InterruptedException exception) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new AsyncOperationException(
          "EXTERNAL_CALL_INTERRUPTED",
          true,
          "The internal operation was interrupted and can be retried.",
          exception);
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof BulkheadFullException) {
        throw new AsyncOperationException(
            "EXTERNAL_CAPACITY_EXHAUSTED", true, "The service is busy. Please try again shortly.");
      }
      if (cause instanceof AsyncOperationException asyncException) {
        throw asyncException;
      }
      throw new AsyncOperationException(
          "EXTERNAL_CALL_UNAVAILABLE",
          true,
          "A required internal service is temporarily unavailable.",
          cause);
    }
  }

  private CircuitBreaker newCircuitBreaker(String name) {
    CircuitBreakerConfig config =
        CircuitBreakerConfig.custom()
            .slidingWindowSize(properties.getExternalCircuitWindow())
            .minimumNumberOfCalls(properties.getExternalCircuitMinimumCalls())
            .failureRateThreshold(properties.getExternalCircuitFailureRate())
            .waitDurationInOpenState(properties.getExternalCircuitOpenDuration())
            .permittedNumberOfCallsInHalfOpenState(properties.getExternalCircuitHalfOpenCalls())
            .ignoreException(
                failure ->
                    failure instanceof AsyncOperationException async
                        && (!async.retryable()
                            || async.code().equals("EXTERNAL_CAPACITY_EXHAUSTED")
                            || async.code().equals("EXTERNAL_CALL_INTERRUPTED")))
            .build();
    return CircuitBreaker.of(name, config);
  }

  private Bulkhead newBulkhead(String name) {
    BulkheadConfig config =
        BulkheadConfig.custom()
            .maxConcurrentCalls(properties.getExternalMaxConcurrency())
            .maxWaitDuration(Duration.ZERO)
            .build();
    return Bulkhead.of(name, config);
  }

  private Duration backoff(int attempt) {
    long multiplier = 1L << Math.min(attempt - 1, 20);
    long delay =
        Math.min(
            properties.getMaximumBackoff().toMillis(),
            properties.getInitialBackoff().toMillis() * multiplier);
    return Duration.ofMillis(delay);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AsyncOperationException(
          "ASYNC_RETRY_INTERRUPTED",
          true,
          "The retry was interrupted and can be resumed.",
          exception);
    }
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }
}
