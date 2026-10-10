package com.tripagent.nexus.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.controller.dto.ApiModels.ApiError;
import com.tripagent.nexus.observability.TraceContextProvider;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class ApiSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final ObjectMapper objectMapper;
  private final TraceContextProvider traceContextProvider;
  private final Clock clock;

  public ApiSecurityErrorHandler(
      ObjectMapper objectMapper, TraceContextProvider traceContextProvider, Clock clock) {
    this.objectMapper = objectMapper;
    this.traceContextProvider = traceContextProvider;
    this.clock = clock;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException, ServletException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    write(
        response,
        HttpStatus.UNAUTHORIZED,
        "AUTHENTICATION_REQUIRED",
        "A valid user session is required.");
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
      throws IOException, ServletException {
    write(
        response,
        HttpStatus.FORBIDDEN,
        "ACCESS_DENIED",
        "You are not allowed to access this resource.");
  }

  private void write(HttpServletResponse response, HttpStatus status, String code, String message)
      throws IOException {
    String traceId = traceContextProvider.currentOrNewTraceId();
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.setHeader("X-Trace-Id", traceId);
    objectMapper.writeValue(
        response.getOutputStream(),
        new ApiError(code, message, List.of(), traceId, clock.instant()));
  }
}
