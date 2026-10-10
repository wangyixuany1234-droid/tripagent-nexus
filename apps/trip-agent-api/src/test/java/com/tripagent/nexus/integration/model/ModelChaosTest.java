package com.tripagent.nexus.integration.model;

import static com.tripagent.nexus.testsupport.ChaosEvidence.duration;
import static com.tripagent.nexus.testsupport.ChaosEvidence.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelMetadata;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelResponse;
import com.tripagent.nexus.integration.model.ModelGatewayModels.TokenUsage;
import com.tripagent.nexus.integration.model.result.QueryRewriteResult;
import com.tripagent.nexus.testsupport.ChaosEvidence;
import jakarta.validation.Validation;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

class ModelChaosTest {
  private static final String VALID =
      """
      {"originalQuery":"synthetic","rewrittenQuery":"Beijing","keywords":["city"],
      "graphEntities":[],"filters":{}}
      """;

  @ParameterizedTest
  @ValueSource(strings = {"timeout", "rateLimit", "serverError", "schema", "long", "empty"})
  void modelFailuresHaveBoundedAttemptsAndRecover(String fault) throws Exception {
    ChaosEvidence.run(
        "model." + fault,
        "IN_PROCESS_PROVIDER_STUB",
        "Production model executor and structured output validator; no external model",
        record -> {
          var properties = new ModelGatewayProperties();
          properties.setTimeout(duration("callTimeoutMs"));
          properties.setMaxAttempts(value("maxAttempts"));
          properties.setInitialBackoff(duration("initialBackoffMs"));
          properties.setMaxConcurrency(1);
          properties.setMaxTotalCalls(value("maxAttempts") + 3);
          var calls = new AtomicInteger();
          var active = new AtomicInteger();
          try (var executor = new ModelCallExecutor(properties, new ModelExceptionClassifier());
              var validators = Validation.buildDefaultValidatorFactory()) {
            var structured =
                new StructuredOutputService(
                    new ObjectMapper().findAndRegisterModules(),
                    validators.getValidator(),
                    null,
                    value("modelMaxResponseChars"));
            var request = new ModelChatRequest("Return JSON", "Synthetic Beijing query");
            record.injected();
            var failure =
                catchThrowableOfType(
                    () -> {
                      if (fault.equals("schema") || fault.equals("long") || fault.equals("empty")) {
                        structured.execute(
                            request,
                            QueryRewriteResult.class,
                            ignored -> {
                              calls.incrementAndGet();
                              return response(
                                  switch (fault) {
                                    case "schema" -> "{invalid";
                                    case "empty" -> "";
                                    default ->
                                        VALID.replace(
                                            "synthetic",
                                            "x".repeat(value("modelMaxResponseChars") + 1));
                                  });
                            });
                      } else {
                        executor.execute(
                            () -> {
                              calls.incrementAndGet();
                              active.incrementAndGet();
                              try {
                                if (fault.equals("rateLimit"))
                                  throw new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);
                                if (fault.equals("serverError"))
                                  throw new HttpServerErrorException(
                                      HttpStatus.SERVICE_UNAVAILABLE);
                                new CountDownLatch(1).await();
                                return "late";
                              } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return "cancelled";
                              } finally {
                                active.decrementAndGet();
                              }
                            });
                      }
                    },
                    ModelGatewayException.class);
            record.detected();
            assertThat(failure).isNotNull();
            String expectedCode =
                switch (fault) {
                  case "timeout" -> "MODEL_TIMEOUT";
                  case "rateLimit" -> "MODEL_RATE_LIMITED";
                  case "serverError" -> "MODEL_PROVIDER_UNAVAILABLE";
                  case "long" -> "MODEL_RESPONSE_TOO_LARGE";
                  default -> "MODEL_OUTPUT_INVALID";
                };
            assertThat(failure.getCode()).isEqualTo(expectedCode);
            assertThat(calls.get())
                .isEqualTo(
                    fault.equals("long")
                        ? 1
                        : fault.equals("schema") || fault.equals("empty")
                            ? 2
                            : value("maxAttempts"));
            record.measure("attempts", calls.get());
            record.measure("failureCode", failure.getCode());
            record.degraded(
                "The model could not provide a valid result; no successful result was fabricated.");
            await()
                .atMost(duration("assertionTimeoutMs"))
                .until(() -> active.get() == value("maximumActiveWorkers"));
            record.cleared();
            assertThat(executor.execute(() -> "recovered")).isEqualTo("recovered");
            assertThat(
                    structured
                        .execute(request, QueryRewriteResult.class, ignored -> response(VALID))
                        .value()
                        .rewrittenQuery())
                .isEqualTo("Beijing");
            record.measure("finalActiveWorkers", active.get());
            record.recovered();
          }
        });
  }

  private static ModelResponse response(String content) {
    return new ModelResponse(
        content, new ModelMetadata("mock", "synthetic", new TokenUsage(0, 0, 0), true));
  }
}
