package com.tripagent.nexus.integration.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelMetadata;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelResponse;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelStreamChunk;
import com.tripagent.nexus.integration.model.ModelGatewayModels.TokenUsage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class FallbackModelGatewayTest {

  private final ModelChatRequest request = new ModelChatRequest("", "hello");
  private ModelGateway primary;
  private ModelGateway fallback;
  private FallbackModelGateway gateway;

  @BeforeEach
  void setUp() {
    primary = mock(ModelGateway.class);
    fallback = mock(ModelGateway.class);
    gateway = new FallbackModelGateway(primary, fallback, new ModelExceptionClassifier());
  }

  @Test
  void usesFallbackForRetryablePrimaryFailureAndMarksMetadata() {
    when(primary.chat(request))
        .thenThrow(
            new ModelGatewayException("MODEL_PROVIDER_UNAVAILABLE", true, "Provider unavailable."));
    when(fallback.chat(request)).thenReturn(mockResponse("fallback answer"));

    ModelResponse response = gateway.chat(request);

    assertThat(response.content()).isEqualTo("fallback answer");
    assertThat(response.metadata().provider()).isEqualTo("mock");
    assertThat(response.metadata().fallback()).isTrue();
  }

  @Test
  void doesNotFallbackForNonRetryableFailure() {
    when(primary.chat(request))
        .thenThrow(new ModelGatewayException("MODEL_OUTPUT_INVALID", false, "Invalid output."));

    assertThatThrownBy(() -> gateway.chat(request))
        .isInstanceOfSatisfying(
            ModelGatewayException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("MODEL_OUTPUT_INVALID"));
    verify(fallback, never()).chat(request);
  }

  @Test
  void fallsBackWhenStreamFailsAndMarksEveryChunk() {
    when(primary.streamChat(request))
        .thenReturn(
            Flux.error(
                new ModelGatewayException("MODEL_RATE_LIMITED", true, "Provider rate limited.")));
    when(fallback.streamChat(request))
        .thenReturn(
            Flux.just(
                new ModelStreamChunk("fallback", false, mockMetadata()),
                new ModelStreamChunk("", true, mockMetadata())));

    List<ModelStreamChunk> chunks = gateway.streamChat(request).collectList().block();

    assertThat(chunks).hasSize(2).allMatch(chunk -> chunk.metadata().fallback());
  }

  private static ModelResponse mockResponse(String content) {
    return new ModelResponse(content, mockMetadata());
  }

  private static ModelMetadata mockMetadata() {
    return new ModelMetadata("mock", "mock-model", TokenUsage.unknown(), false);
  }
}
