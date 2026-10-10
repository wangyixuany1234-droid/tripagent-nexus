package com.tripagent.nexus.integration.model;

import java.util.List;
import java.util.Objects;

public final class ModelGatewayModels {

  private ModelGatewayModels() {}

  public record ModelChatRequest(String systemPrompt, String userPrompt, String promptVersion) {
    public ModelChatRequest(String systemPrompt, String userPrompt) {
      this(systemPrompt, userPrompt, "unspecified");
    }

    public ModelChatRequest {
      systemPrompt = systemPrompt == null ? "" : systemPrompt;
      if (userPrompt == null || userPrompt.isBlank()) {
        throw new IllegalArgumentException("userPrompt must not be blank");
      }
      promptVersion =
          promptVersion == null || promptVersion.isBlank() ? "unspecified" : promptVersion;
    }
  }

  public record ModelImage(String mediaType, byte[] data) {
    public ModelImage {
      if (mediaType == null || !mediaType.startsWith("image/")) {
        throw new IllegalArgumentException("mediaType must be an image type");
      }
      if (data == null || data.length == 0) {
        throw new IllegalArgumentException("image data must not be empty");
      }
      data = data.clone();
    }

    @Override
    public byte[] data() {
      return data.clone();
    }
  }

  public record MultimodalChatRequest(
      String systemPrompt, String userPrompt, List<ModelImage> images, String promptVersion) {
    public MultimodalChatRequest(String systemPrompt, String userPrompt, List<ModelImage> images) {
      this(systemPrompt, userPrompt, images, "unspecified");
    }

    public MultimodalChatRequest {
      systemPrompt = systemPrompt == null ? "" : systemPrompt;
      if (userPrompt == null || userPrompt.isBlank()) {
        throw new IllegalArgumentException("userPrompt must not be blank");
      }
      images = List.copyOf(Objects.requireNonNull(images, "images must not be null"));
      if (images.isEmpty()) {
        throw new IllegalArgumentException("images must not be empty");
      }
      promptVersion =
          promptVersion == null || promptVersion.isBlank() ? "unspecified" : promptVersion;
    }
  }

  public record TokenUsage(long inputTokens, long outputTokens, long totalTokens) {
    public static TokenUsage unknown() {
      return new TokenUsage(0, 0, 0);
    }
  }

  public record ModelMetadata(
      String provider, String model, TokenUsage tokenUsage, boolean fallback) {
    public ModelMetadata {
      Objects.requireNonNull(provider, "provider must not be null");
      Objects.requireNonNull(model, "model must not be null");
      tokenUsage = tokenUsage == null ? TokenUsage.unknown() : tokenUsage;
    }

    public ModelMetadata asFallback() {
      return new ModelMetadata(provider, model, tokenUsage, true);
    }
  }

  public record ModelResponse(String content, ModelMetadata metadata) {
    public ModelResponse {
      Objects.requireNonNull(content, "content must not be null");
      Objects.requireNonNull(metadata, "metadata must not be null");
    }

    public ModelResponse asFallback() {
      return new ModelResponse(content, metadata.asFallback());
    }
  }

  public record StructuredModelResponse<T>(T value, ModelMetadata metadata) {
    public StructuredModelResponse {
      Objects.requireNonNull(value, "value must not be null");
      Objects.requireNonNull(metadata, "metadata must not be null");
    }

    public StructuredModelResponse<T> asFallback() {
      return new StructuredModelResponse<>(value, metadata.asFallback());
    }
  }

  public record ModelStreamChunk(String content, boolean done, ModelMetadata metadata) {
    public ModelStreamChunk {
      Objects.requireNonNull(content, "content must not be null");
      Objects.requireNonNull(metadata, "metadata must not be null");
    }

    public ModelStreamChunk asFallback() {
      return new ModelStreamChunk(content, done, metadata.asFallback());
    }
  }

  public record ModelHealth(boolean available, String provider, String model, String message) {
    public ModelHealth {
      Objects.requireNonNull(provider, "provider must not be null");
      Objects.requireNonNull(model, "model must not be null");
      Objects.requireNonNull(message, "message must not be null");
    }

    public ModelHealth asFallback() {
      return new ModelHealth(available, provider, model, "fallback: " + message);
    }
  }
}
