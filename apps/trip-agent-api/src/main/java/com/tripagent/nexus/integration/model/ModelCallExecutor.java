package com.tripagent.nexus.integration.model;

import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

public class ModelCallExecutor implements AutoCloseable {

  private final Duration timeout;
  private final int maxAttempts;
  private final Duration initialBackoff;
  private final double backoffMultiplier;
  private final Duration concurrencyWait;
  private final Semaphore concurrency;
  private final int maxTotalCalls;
  private final AtomicInteger totalCalls = new AtomicInteger();
  private final ModelExceptionClassifier exceptionClassifier;
  private final ExecutorService executor;
  private final Sleeper sleeper;

  public ModelCallExecutor(
      ModelGatewayProperties properties, ModelExceptionClassifier exceptionClassifier) {
    this(
        properties,
        exceptionClassifier,
        Executors.newVirtualThreadPerTaskExecutor(),
        duration -> Thread.sleep(duration));
  }

  ModelCallExecutor(
      ModelGatewayProperties properties,
      ModelExceptionClassifier exceptionClassifier,
      ExecutorService executor,
      Sleeper sleeper) {
    this.timeout = properties.getTimeout();
    this.maxAttempts = properties.getMaxAttempts();
    this.initialBackoff = properties.getInitialBackoff();
    this.backoffMultiplier = properties.getBackoffMultiplier();
    this.concurrencyWait = properties.getConcurrencyWait();
    this.concurrency = new Semaphore(properties.getMaxConcurrency());
    this.maxTotalCalls = properties.getMaxTotalCalls();
    this.exceptionClassifier = exceptionClassifier;
    this.executor = executor;
    this.sleeper = sleeper;
  }

  public <T> T execute(Supplier<T> call) {
    Objects.requireNonNull(call, "call must not be null");
    ModelGatewayException lastFailure = null;
    for (int attempt = 0; attempt < maxAttempts; attempt++) {
      try {
        return executeTimed(call);
      } catch (RuntimeException exception) {
        lastFailure = exceptionClassifier.classify(exception);
        if (!lastFailure.isRetryable()
            || attempt + 1 >= maxAttempts
            || lastFailure.getCode().equals("MODEL_CONCURRENCY_LIMIT")) {
          throw lastFailure;
        }
        sleepBeforeRetry(attempt);
      }
    }
    throw lastFailure == null
        ? new ModelGatewayException("MODEL_CALL_FAILED", false, "The model request failed.")
        : lastFailure;
  }

  public <T> Flux<T> executeStream(Supplier<Flux<T>> call) {
    Objects.requireNonNull(call, "call must not be null");
    return Flux.defer(
        () -> {
          acquirePermit();
          Flux<T> source =
              Flux.defer(
                  () -> {
                    acquireCallBudget();
                    return call.get();
                  });
          return source
              .timeout(timeout)
              .retryWhen(
                  Retry.from(
                      signals ->
                          signals.concatMap(
                              signal -> {
                                ModelGatewayException failure =
                                    exceptionClassifier.classify(signal.failure());
                                if (!failure.isRetryable()
                                    || signal.totalRetries() + 1 >= maxAttempts) {
                                  return Mono.error(failure);
                                }
                                return Mono.delay(backoff((int) signal.totalRetries()));
                              })))
              .onErrorMap(exceptionClassifier::classify)
              .doFinally(signalType -> concurrency.release());
        });
  }

  private <T> T executeTimed(Supplier<T> call) {
    acquirePermit();
    // 0: queued, 1: running, 2: released. Cancellation must not free a running provider's slot.
    AtomicInteger state = new AtomicInteger();
    Future<T> future;
    try {
      acquireCallBudget();
      future =
          executor.submit(
              () -> {
                if (!state.compareAndSet(0, 1)) {
                  return null;
                }
                try {
                  return call.get();
                } finally {
                  state.set(2);
                  concurrency.release();
                }
              });
    } catch (RuntimeException failure) {
      concurrency.release();
      throw failure;
    }
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      future.cancel(true);
      throw new ModelGatewayException(
          "MODEL_TIMEOUT", true, "The model request timed out.", exception);
    } catch (InterruptedException exception) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new ModelGatewayException(
          "MODEL_CALL_INTERRUPTED", false, "The model request was interrupted.", exception);
    } catch (java.util.concurrent.ExecutionException exception) {
      throw exceptionClassifier.classify(exception);
    } finally {
      if (state.compareAndSet(0, 2)) {
        concurrency.release();
      }
    }
  }

  private void acquireCallBudget() {
    int callNumber = totalCalls.incrementAndGet();
    if (callNumber > maxTotalCalls) {
      totalCalls.decrementAndGet();
      throw new ModelGatewayException(
          "MODEL_CALL_BUDGET_EXHAUSTED",
          false,
          "The configured model call budget has been exhausted.");
    }
  }

  private void acquirePermit() {
    try {
      if (!concurrency.tryAcquire(concurrencyWait.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new ModelGatewayException(
            "MODEL_CONCURRENCY_LIMIT", true, "The model concurrency limit has been reached.");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ModelGatewayException(
          "MODEL_CALL_INTERRUPTED", false, "The model request was interrupted.", exception);
    }
  }

  private void sleepBeforeRetry(int completedAttempt) {
    try {
      sleeper.sleep(backoff(completedAttempt));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ModelGatewayException(
          "MODEL_CALL_INTERRUPTED", false, "The model retry was interrupted.", exception);
    }
  }

  private Duration backoff(int completedAttempt) {
    double millis =
        initialBackoff.toMillis() * Math.pow(backoffMultiplier, Math.max(0, completedAttempt));
    return Duration.ofMillis(Math.max(0L, Math.round(millis)));
  }

  @Override
  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }
}
