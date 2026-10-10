package com.tripagent.nexus.observability;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

class TraceOperationsTest {

  @Test
  void cancelledStreamRecordsAnExplicitUnsuccessfulOutcome() {
    Tracer tracer = mock(Tracer.class);
    Span span = mock(Span.class, Answers.RETURNS_SELF);
    Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
    when(tracer.nextSpan()).thenReturn(span);
    when(tracer.withSpan(span)).thenReturn(scope);
    TraceOperations traces = new TraceOperations(tracer, new SimpleMeterRegistry());

    Disposable subscription =
        traces
            .traceFlux(
                "model.call",
                "tripagent.model.latency",
                Map.of("gen_ai.operation.name", "stream"),
                Flux::never)
            .subscribe();
    subscription.dispose();

    verify(span).tag("operation.success", "false");
    verify(span).tag("error.type", "Cancelled");
    verify(span).end();
  }
}
