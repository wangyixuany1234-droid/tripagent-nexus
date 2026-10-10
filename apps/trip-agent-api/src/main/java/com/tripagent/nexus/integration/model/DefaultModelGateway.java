package com.tripagent.nexus.integration.model;

import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelHealth;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelMetadata;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelResponse;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelStreamChunk;
import com.tripagent.nexus.integration.model.ModelGatewayModels.MultimodalChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.StructuredModelResponse;
import com.tripagent.nexus.integration.model.ModelGatewayModels.TokenUsage;
import com.tripagent.nexus.observability.TraceOperations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;

public class DefaultModelGateway implements ModelGateway {

  private final ModelProviderClient providerClient;
  private final ModelCallExecutor callExecutor;
  private final StructuredOutputService structuredOutputService;
  private final MeterRegistry meterRegistry;
  private final TraceOperations traces;
  private final ModelGatewayProperties properties;

  public DefaultModelGateway(
      ModelProviderClient providerClient,
      ModelCallExecutor callExecutor,
      StructuredOutputService structuredOutputService,
      MeterRegistry meterRegistry) {
    this(providerClient, callExecutor, structuredOutputService, meterRegistry, null, null);
  }

  public DefaultModelGateway(
      ModelProviderClient providerClient,
      ModelCallExecutor callExecutor,
      StructuredOutputService structuredOutputService,
      MeterRegistry meterRegistry,
      TraceOperations traces,
      ModelGatewayProperties properties) {
    this.providerClient = providerClient;
    this.callExecutor = callExecutor;
    this.structuredOutputService = structuredOutputService;
    this.meterRegistry = meterRegistry;
    this.traces = traces;
    this.properties = properties;
  }

  @Override
  public ModelResponse chat(ModelChatRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    ModelResponse response =
        observed(
            "chat",
            request.promptVersion(),
            () -> callExecutor.execute(() -> providerClient.chat(request)));
    recordUsage(response.metadata());
    return response;
  }

  @Override
  public <T> StructuredModelResponse<T> structuredChat(
      ModelChatRequest request, Class<T> responseType) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(responseType, "responseType must not be null");
    return structuredOutputService.execute(request, responseType, this::chat);
  }

  @Override
  public ModelResponse multimodalChat(MultimodalChatRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    ModelResponse response =
        observed(
            "multimodal",
            request.promptVersion(),
            () -> callExecutor.execute(() -> providerClient.multimodalChat(request)));
    recordUsage(response.metadata());
    return response;
  }

  @Override
  public Flux<ModelStreamChunk> streamChat(ModelChatRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    java.util.function.Supplier<Flux<ModelStreamChunk>> stream =
        () -> callExecutor.executeStream(() -> providerClient.streamChat(request));
    Flux<ModelStreamChunk> observed =
        traces == null
            ? stream.get()
            : traces.traceFlux(
                "model.call",
                "tripagent.model.latency",
                modelAttributes("stream", request.promptVersion()),
                stream,
                (span, chunk) -> tagUsage(span, chunk.metadata()));
    return observed.doOnNext(
        chunk -> {
          if (chunk.done()) {
            recordUsage(chunk.metadata());
          }
        });
  }

  @Override
  public ModelHealth healthCheck() {
    return callExecutor.execute(providerClient::healthCheck);
  }

  private <T> T observed(
      String operation, String promptVersion, java.util.function.Supplier<T> call) {
    if (traces == null) {
      return call.get();
    }
    return traces.trace(
        "model.call",
        "tripagent.model.latency",
        modelAttributes(operation, promptVersion),
        call,
        (span, response) -> {
          if (response instanceof ModelResponse modelResponse) {
            tagUsage(span, modelResponse.metadata());
          }
        });
  }

  private Map<String, String> modelAttributes(String operation, String promptVersion) {
    return Map.of(
        "gen_ai.operation.name",
        operation,
        "gen_ai.provider.name",
        properties.getProvider().name(),
        "gen_ai.request.model",
        properties.getModel(),
        "gen_ai.prompt.version",
        promptVersion);
  }

  private void recordUsage(ModelMetadata metadata) {
    TokenUsage usage = metadata.tokenUsage();
    increment(metadata, "input", usage.inputTokens());
    increment(metadata, "output", usage.outputTokens());
    increment(metadata, "total", usage.totalTokens());
  }

  private static void tagUsage(Span span, ModelMetadata metadata) {
    TokenUsage usage = metadata.tokenUsage();
    span.tag("gen_ai.response.model", metadata.model());
    span.tag("gen_ai.usage.input_tokens", Long.toString(usage.inputTokens()));
    span.tag("gen_ai.usage.output_tokens", Long.toString(usage.outputTokens()));
    span.tag("gen_ai.usage.total_tokens", Long.toString(usage.totalTokens()));
  }

  private void increment(ModelMetadata metadata, String kind, long tokens) {
    if (tokens <= 0) {
      return;
    }
    meterRegistry
        .counter(
            "tripagent.model.tokens",
            "provider",
            metadata.provider(),
            "model",
            metadata.model(),
            "kind",
            kind)
        .increment(tokens);
  }
}
