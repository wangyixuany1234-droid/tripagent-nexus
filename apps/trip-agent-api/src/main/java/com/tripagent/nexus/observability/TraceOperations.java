package com.tripagent.nexus.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

@Component
public class TraceOperations {

  private final Tracer tracer;
  private final MeterRegistry meterRegistry;

  public TraceOperations(Tracer tracer, MeterRegistry meterRegistry) {
    this.tracer = tracer;
    this.meterRegistry = meterRegistry;
  }

  public <T> T trace(
      String spanName, String metricName, Map<String, String> attributes, Supplier<T> operation) {
    return trace(spanName, metricName, attributes, operation, (span, result) -> {});
  }

  public <T> T trace(
      String spanName,
      String metricName,
      Map<String, String> attributes,
      Supplier<T> operation,
      BiConsumer<Span, T> resultTags) {
    Span span = tracer.nextSpan().name(spanName);
    attributes.forEach(span::tag);
    span.start();
    long started = System.nanoTime();
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      T result = operation.get();
      resultTags.accept(span, result);
      span.tag("operation.success", "true");
      return result;
    } catch (RuntimeException exception) {
      span.tag("operation.success", "false");
      span.tag("error.type", exception.getClass().getSimpleName());
      span.error(exception);
      throw exception;
    } finally {
      long elapsed = System.nanoTime() - started;
      span.tag("operation.duration_ms", Long.toString(TimeUnit.NANOSECONDS.toMillis(elapsed)));
      Timer.builder(metricName)
          .tags(flatten(attributes))
          .register(meterRegistry)
          .record(elapsed, TimeUnit.NANOSECONDS);
      span.end();
    }
  }

  public void trace(
      String spanName, String metricName, Map<String, String> attributes, Runnable operation) {
    trace(
        spanName,
        metricName,
        attributes,
        () -> {
          operation.run();
          return null;
        });
  }

  public <T> Flux<T> traceFlux(
      String spanName,
      String metricName,
      Map<String, String> attributes,
      Supplier<Flux<T>> operation) {
    return traceFlux(spanName, metricName, attributes, operation, (span, item) -> {});
  }

  public <T> Flux<T> traceFlux(
      String spanName,
      String metricName,
      Map<String, String> attributes,
      Supplier<Flux<T>> operation,
      BiConsumer<Span, T> itemTags) {
    return Flux.defer(
        () -> {
          Span span = tracer.nextSpan().name(spanName);
          attributes.forEach(span::tag);
          span.start();
          long started = System.nanoTime();
          Flux<T> result;
          try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            result = operation.get();
          } catch (RuntimeException exception) {
            finish(span, metricName, attributes, started, exception);
            throw exception;
          }
          return result
              .doOnNext(item -> itemTags.accept(span, item))
              .doOnComplete(() -> span.tag("operation.success", "true"))
              .doOnError(
                  exception -> {
                    span.tag("operation.success", "false");
                    span.tag("error.type", exception.getClass().getSimpleName());
                    span.error(exception);
                  })
              .doFinally(
                  signal -> {
                    if (signal == SignalType.CANCEL) {
                      span.tag("operation.success", "false");
                      span.tag("error.type", "Cancelled");
                    }
                    finish(span, metricName, attributes, started, null);
                  });
        });
  }

  private void finish(
      Span span,
      String metricName,
      Map<String, String> attributes,
      long started,
      RuntimeException startupFailure) {
    long elapsed = System.nanoTime() - started;
    if (startupFailure != null) {
      span.tag("operation.success", "false");
      span.tag("error.type", startupFailure.getClass().getSimpleName());
      span.error(startupFailure);
    }
    span.tag("operation.duration_ms", Long.toString(TimeUnit.NANOSECONDS.toMillis(elapsed)));
    Timer.builder(metricName)
        .tags(flatten(attributes))
        .register(meterRegistry)
        .record(elapsed, TimeUnit.NANOSECONDS);
    span.end();
  }

  private static String[] flatten(Map<String, String> attributes) {
    String[] tags = new String[attributes.size() * 2];
    int index = 0;
    for (Map.Entry<String, String> entry : attributes.entrySet()) {
      tags[index++] = entry.getKey();
      tags[index++] = entry.getValue();
    }
    return tags;
  }
}
