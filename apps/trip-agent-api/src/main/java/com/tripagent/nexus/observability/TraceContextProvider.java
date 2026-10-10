package com.tripagent.nexus.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TraceContextProvider {

  private final Tracer tracer;

  public TraceContextProvider(Tracer tracer) {
    this.tracer = tracer;
  }

  public String currentOrNewTraceId() {
    Span span = tracer.currentSpan();
    if (span != null && !span.context().traceId().isBlank()) {
      return span.context().traceId();
    }
    return UUID.randomUUID().toString().replace("-", "");
  }
}
