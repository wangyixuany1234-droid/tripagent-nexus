package com.tripagent.nexus.common.error;

import com.tripagent.nexus.controller.dto.ApiModels.ApiError;
import com.tripagent.nexus.controller.dto.ApiModels.ErrorDetail;
import com.tripagent.nexus.guardrail.UploadPolicy.UploadPolicyViolation;
import com.tripagent.nexus.integration.model.ModelGatewayException;
import com.tripagent.nexus.observability.TraceContextProvider;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final TraceContextProvider traceContextProvider;
  private final Clock clock;

  public GlobalExceptionHandler(TraceContextProvider traceContextProvider, Clock clock) {
    this.traceContextProvider = traceContextProvider;
    this.clock = clock;
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> handleApiException(ApiException exception) {
    HttpHeaders headers = new HttpHeaders();
    if (exception.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
      headers.set(HttpHeaders.RETRY_AFTER, "60");
    }
    return response(
        exception.getStatus(),
        exception.getCode(),
        exception.getMessage(),
        exception.getDetails(),
        headers);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
    List<ErrorDetail> details =
        exception.getBindingResult().getFieldErrors().stream()
            .map(
                error ->
                    new ErrorDetail(
                        "/" + error.getField().replace('.', '/'),
                        "FIELD_INVALID",
                        error.getDefaultMessage() == null
                            ? "Invalid value"
                            : error.getDefaultMessage()))
            .toList();
    return response(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "VALIDATION_FAILED",
        "One or more fields are invalid.",
        details);
  }

  @ExceptionHandler({
    MissingRequestHeaderException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageConversionException.class,
    HandlerMethodValidationException.class,
    ConstraintViolationException.class,
    MultipartException.class,
    MissingServletRequestPartException.class,
  })
  ResponseEntity<ApiError> handleBadRequest(Exception exception) {
    return response(
        HttpStatus.BAD_REQUEST,
        "REQUEST_INVALID",
        "The request could not be parsed or is missing required input.",
        List.of());
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiError> handleUnsupportedMediaType(
      HttpMediaTypeNotSupportedException exception) {
    if (exception.getContentType() == null) {
      return response(
          HttpStatus.BAD_REQUEST,
          "REQUEST_INVALID",
          "The request body or content type is missing.",
          List.of());
    }
    return response(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "CONTENT_TYPE_UNSUPPORTED",
        "The request content type is not supported.",
        List.of());
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiError> handleUnsupportedMethod(
      HttpRequestMethodNotSupportedException exception) {
    HttpHeaders headers = new HttpHeaders();
    if (exception.getSupportedHttpMethods() != null) {
      headers.setAllow(exception.getSupportedHttpMethods());
    }
    return response(
        HttpStatus.METHOD_NOT_ALLOWED,
        "METHOD_NOT_ALLOWED",
        "The HTTP method is not supported for this resource.",
        List.of(),
        headers);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiError> handleNotFound(NoResourceFoundException exception) {
    return response(
        HttpStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND",
        "The requested resource was not found.",
        List.of());
  }

  @ExceptionHandler(UploadPolicyViolation.class)
  ResponseEntity<ApiError> handleUploadPolicy(UploadPolicyViolation exception) {
    HttpStatus status =
        switch (exception.getMessage()) {
          case "UPLOAD_SIZE_INVALID" -> HttpStatus.PAYLOAD_TOO_LARGE;
          case "UPLOAD_MIME_TYPE_DENIED",
              "UPLOAD_EXTENSION_DENIED",
              "UPLOAD_MIME_MISMATCH",
              "UPLOAD_EXECUTABLE_DENIED" ->
              HttpStatus.UNSUPPORTED_MEDIA_TYPE;
          default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    return response(
        status,
        exception.getMessage(),
        "The upload does not satisfy the configured policy.",
        List.of());
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ApiError> handleMaxUploadSize() {
    return response(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "UPLOAD_SIZE_INVALID",
        "The upload exceeds the configured size limit.",
        List.of());
  }

  @ExceptionHandler(ModelGatewayException.class)
  ResponseEntity<ApiError> handleModelFailure(ModelGatewayException exception) {
    HttpStatus status =
        switch (exception.getCode()) {
          case "MODEL_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
          case "MODEL_CALL_BUDGET_EXHAUSTED" -> HttpStatus.TOO_MANY_REQUESTS;
          case "MODEL_RATE_LIMITED", "MODEL_PROVIDER_UNAVAILABLE", "MODEL_CONCURRENCY_LIMIT" ->
              HttpStatus.SERVICE_UNAVAILABLE;
          default -> HttpStatus.BAD_GATEWAY;
        };
    return response(status, exception.getCode(), exception.getMessage(), List.of());
  }

  @ExceptionHandler({
    DataIntegrityViolationException.class,
    ObjectOptimisticLockingFailureException.class,
    IllegalStateException.class,
  })
  ResponseEntity<ApiError> handleConflict(Exception exception) {
    return response(
        HttpStatus.CONFLICT,
        "RESOURCE_CONFLICT",
        "The operation conflicts with current resource state.",
        List.of());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> handleUnexpected(Exception exception) {
    log.error("Unhandled request failure", exception);
    return response(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "The request could not be completed.",
        List.of());
  }

  private ResponseEntity<ApiError> response(
      HttpStatus status, String code, String message, List<ErrorDetail> details) {
    return response(status, code, message, details, HttpHeaders.EMPTY);
  }

  private ResponseEntity<ApiError> response(
      HttpStatus status,
      String code,
      String message,
      List<ErrorDetail> details,
      HttpHeaders headers) {
    return ResponseEntity.status(status)
        .headers(headers)
        .body(
            new ApiError(
                code,
                message,
                details,
                traceContextProvider.currentOrNewTraceId(),
                clock.instant()));
  }
}
