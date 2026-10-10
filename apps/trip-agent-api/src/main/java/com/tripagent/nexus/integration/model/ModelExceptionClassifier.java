package com.tripagent.nexus.integration.model;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

public class ModelExceptionClassifier {

  public ModelGatewayException classify(Throwable failure) {
    Throwable cause = unwrap(failure);
    if (cause instanceof ModelGatewayException exception) {
      return exception;
    }
    if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) {
      return new ModelGatewayException(
          "MODEL_TIMEOUT", true, "The model request timed out.", cause);
    }
    if (cause instanceof BulkheadFullException) {
      return new ModelGatewayException(
          "MODEL_CONCURRENCY_LIMIT", true, "The model concurrency limit has been reached.", cause);
    }
    if (cause instanceof RestClientResponseException responseException) {
      return classifyStatus(responseException.getStatusCode(), cause);
    }
    if (cause instanceof ResourceAccessException) {
      return new ModelGatewayException(
          "MODEL_PROVIDER_UNAVAILABLE", true, "The model provider could not be reached.", cause);
    }

    String simpleName = cause.getClass().getSimpleName();
    if ("TransientAiException".equals(simpleName)) {
      return new ModelGatewayException(
          "MODEL_PROVIDER_UNAVAILABLE",
          true,
          "The model provider reported a transient failure.",
          cause);
    }
    if ("NonTransientAiException".equals(simpleName)) {
      return new ModelGatewayException(
          "MODEL_REQUEST_REJECTED", false, "The model provider rejected the request.", cause);
    }
    return new ModelGatewayException(
        "MODEL_CALL_FAILED", false, "The model request failed.", cause);
  }

  private static ModelGatewayException classifyStatus(HttpStatusCode status, Throwable cause) {
    if (status.value() == 429) {
      return new ModelGatewayException(
          "MODEL_RATE_LIMITED", true, "The model provider rate limit was reached.", cause);
    }
    if (status.is5xxServerError() || status.value() == 408) {
      return new ModelGatewayException(
          "MODEL_PROVIDER_UNAVAILABLE",
          true,
          "The model provider is temporarily unavailable.",
          cause);
    }
    return new ModelGatewayException(
        "MODEL_REQUEST_REJECTED", false, "The model provider rejected the request.", cause);
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable current = failure;
    while ((current instanceof CompletionException || current instanceof ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}
