package com.tripagent.nexus.integration.model;

import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelHealth;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelResponse;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelStreamChunk;
import com.tripagent.nexus.integration.model.ModelGatewayModels.MultimodalChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.StructuredModelResponse;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

public class FallbackModelGateway implements ModelGateway {

  private static final Logger log = LoggerFactory.getLogger(FallbackModelGateway.class);

  private final ModelGateway primary;
  private final ModelGateway fallback;
  private final ModelExceptionClassifier exceptionClassifier;

  public FallbackModelGateway(
      ModelGateway primary, ModelGateway fallback, ModelExceptionClassifier exceptionClassifier) {
    this.primary = primary;
    this.fallback = fallback;
    this.exceptionClassifier = exceptionClassifier;
  }

  @Override
  public ModelResponse chat(ModelChatRequest request) {
    FallbackResult<ModelResponse> result =
        withFallback(() -> primary.chat(request), () -> fallback.chat(request));
    return result.fallbackUsed() ? result.value().asFallback() : result.value();
  }

  @Override
  public <T> StructuredModelResponse<T> structuredChat(
      ModelChatRequest request, Class<T> responseType) {
    FallbackResult<StructuredModelResponse<T>> result =
        withFallback(
            () -> primary.structuredChat(request, responseType),
            () -> fallback.structuredChat(request, responseType));
    return result.fallbackUsed() ? result.value().asFallback() : result.value();
  }

  @Override
  public ModelResponse multimodalChat(MultimodalChatRequest request) {
    FallbackResult<ModelResponse> result =
        withFallback(() -> primary.multimodalChat(request), () -> fallback.multimodalChat(request));
    return result.fallbackUsed() ? result.value().asFallback() : result.value();
  }

  @Override
  public Flux<ModelStreamChunk> streamChat(ModelChatRequest request) {
    return Flux.defer(() -> primary.streamChat(request))
        .onErrorResume(
            failure -> {
              ModelGatewayException classified = exceptionClassifier.classify(failure);
              if (!classified.isRetryable()) {
                return Flux.error(classified);
              }
              log.warn(
                  "Model fallback activated operation=streamChat reason={}", classified.getCode());
              return fallback.streamChat(request).map(ModelStreamChunk::asFallback);
            });
  }

  @Override
  public ModelHealth healthCheck() {
    ModelHealth primaryHealth = primary.healthCheck();
    if (primaryHealth.available()) {
      return primaryHealth;
    }
    return fallback.healthCheck().asFallback();
  }

  private <T> FallbackResult<T> withFallback(Supplier<T> primaryCall, Supplier<T> fallbackCall) {
    try {
      return new FallbackResult<>(primaryCall.get(), false);
    } catch (RuntimeException failure) {
      ModelGatewayException classified = exceptionClassifier.classify(failure);
      if (!classified.isRetryable()) {
        throw classified;
      }
      log.warn("Model fallback activated reason={}", classified.getCode());
      return new FallbackResult<>(fallbackCall.get(), true);
    }
  }

  private record FallbackResult<T>(T value, boolean fallbackUsed) {}
}
