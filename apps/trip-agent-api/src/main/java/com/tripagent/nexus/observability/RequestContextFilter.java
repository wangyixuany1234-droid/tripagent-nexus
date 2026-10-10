package com.tripagent.nexus.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component("tripAgentRequestContextFilter")
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class RequestContextFilter extends OncePerRequestFilter {

  public static final String REQUEST_ID_HEADER = "X-Request-Id";
  public static final String TRACE_ID_HEADER = "X-Trace-Id";

  private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

  private final Tracer tracer;

  public RequestContextFilter(Tracer tracer) {
    this.tracer = tracer;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = request.getHeader(REQUEST_ID_HEADER);
    if (requestId == null || !SAFE_REQUEST_ID.matcher(requestId).matches()) {
      requestId = UUID.randomUUID().toString();
    }

    Span span = tracer.currentSpan();
    String traceId = span == null ? null : span.context().traceId();
    response.setHeader(REQUEST_ID_HEADER, requestId);
    if (traceId != null && !traceId.isBlank()) {
      response.setHeader(TRACE_ID_HEADER, traceId);
    }

    try (MDC.MDCCloseable ignoredRequestId = MDC.putCloseable("requestId", requestId);
        MDC.MDCCloseable ignoredTraceId =
            MDC.putCloseable("traceId", traceId == null ? "" : traceId)) {
      filterChain.doFilter(request, response);
    }
  }
}
