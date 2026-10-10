package com.tripagent.nexus.integration.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class ModelExceptionClassifierTest {

  private final ModelExceptionClassifier classifier = new ModelExceptionClassifier();

  @Test
  void classifies429AsRetryableRateLimit() {
    ModelGatewayException result =
        classifier.classify(
            HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "rate limited",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8));

    assertThat(result.getCode()).isEqualTo("MODEL_RATE_LIMITED");
    assertThat(result.isRetryable()).isTrue();
  }

  @Test
  void classifies5xxAsRetryableProviderFailure() {
    ModelGatewayException result =
        classifier.classify(
            HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY,
                "upstream failure",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8));

    assertThat(result.getCode()).isEqualTo("MODEL_PROVIDER_UNAVAILABLE");
    assertThat(result.isRetryable()).isTrue();
  }

  @Test
  void classifiesOther4xxAsNonRetryableRequestFailure() {
    ModelGatewayException result =
        classifier.classify(
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "bad request",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8));

    assertThat(result.getCode()).isEqualTo("MODEL_REQUEST_REJECTED");
    assertThat(result.isRetryable()).isFalse();
  }

  @Test
  void classifiesTimeoutAsRetryable() {
    ModelGatewayException result = classifier.classify(new HttpTimeoutException("timed out"));

    assertThat(result.getCode()).isEqualTo("MODEL_TIMEOUT");
    assertThat(result.isRetryable()).isTrue();
  }

  @Test
  void classifiesJavaTimeoutNestedInExecutionException() {
    TimeoutException timeout = new TimeoutException("synthetic timeout");

    ModelGatewayException result = classifier.classify(new ExecutionException(timeout));

    assertThat(result.getCode()).isEqualTo("MODEL_TIMEOUT");
    assertThat(result.isRetryable()).isTrue();
    assertThat(result.getCause()).isSameAs(timeout);
  }

  @Test
  void classifiesBulkheadSaturationAsRetryableConcurrencyLimit() {
    ModelGatewayException result = classifier.classify(mock(BulkheadFullException.class));

    assertThat(result.getCode()).isEqualTo("MODEL_CONCURRENCY_LIMIT");
    assertThat(result.isRetryable()).isTrue();
  }

  @Test
  void classifiesNetworkAccessFailureAsRetryableProviderFailure() {
    ResourceAccessException failure =
        new ResourceAccessException("synthetic provider connection failure");

    ModelGatewayException result = classifier.classify(failure);

    assertThat(result.getCode()).isEqualTo("MODEL_PROVIDER_UNAVAILABLE");
    assertThat(result.isRetryable()).isTrue();
    assertThat(result.getCause()).isSameAs(failure);
  }

  @Test
  void classifiesFrameworkTransientFailureByStableTypeName() {
    TransientAiException failure = new TransientAiException();

    ModelGatewayException result = classifier.classify(new CompletionException(failure));

    assertThat(result.getCode()).isEqualTo("MODEL_PROVIDER_UNAVAILABLE");
    assertThat(result.isRetryable()).isTrue();
    assertThat(result.getCause()).isSameAs(failure);
  }

  @Test
  void classifiesFrameworkNonTransientFailureByStableTypeName() {
    ModelGatewayException result = classifier.classify(new NonTransientAiException());

    assertThat(result.getCode()).isEqualTo("MODEL_REQUEST_REJECTED");
    assertThat(result.isRetryable()).isFalse();
  }

  @Test
  void classifiesUnknownAndEmptyCompletionFailuresAsNonRetryable() {
    ModelGatewayException unknown = classifier.classify(new IllegalStateException("synthetic"));
    ModelGatewayException emptyCompletion =
        classifier.classify(new CompletionException((Throwable) null));

    assertThat(unknown.getCode()).isEqualTo("MODEL_CALL_FAILED");
    assertThat(unknown.isRetryable()).isFalse();
    assertThat(emptyCompletion.getCode()).isEqualTo("MODEL_CALL_FAILED");
    assertThat(emptyCompletion.isRetryable()).isFalse();
  }

  @Test
  void classifiesRequestTimeoutStatusAsRetryableProviderFailure() {
    ModelGatewayException result =
        classifier.classify(
            HttpClientErrorException.create(
                HttpStatus.REQUEST_TIMEOUT,
                "request timeout",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8));

    assertThat(result.getCode()).isEqualTo("MODEL_PROVIDER_UNAVAILABLE");
    assertThat(result.isRetryable()).isTrue();
  }

  private static final class TransientAiException extends RuntimeException {}

  private static final class NonTransientAiException extends RuntimeException {}
}
