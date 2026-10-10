package com.tripagent.nexus.integration.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelMetadata;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelResponse;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelStreamChunk;
import com.tripagent.nexus.integration.model.ModelGatewayModels.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class DefaultModelGatewayTest {

  private ModelProviderClient providerClient;
  private ModelCallExecutor callExecutor;
  private SimpleMeterRegistry meterRegistry;
  private DefaultModelGateway gateway;

  @BeforeEach
  void setUp() {
    ModelGatewayProperties properties = new ModelGatewayProperties();
    properties.setMaxAttempts(1);
    properties.setTimeout(Duration.ofSeconds(1));
    providerClient = mock(ModelProviderClient.class);
    callExecutor = new ModelCallExecutor(properties, new ModelExceptionClassifier());
    meterRegistry = new SimpleMeterRegistry();
    gateway =
        new DefaultModelGateway(
            providerClient,
            callExecutor,
            new StructuredOutputService(
                new ObjectMapper().findAndRegisterModules(),
                Validation.buildDefaultValidatorFactory().getValidator()),
            meterRegistry);
  }

  @AfterEach
  void tearDown() {
    callExecutor.close();
    meterRegistry.close();
  }

  @Test
  void returnsProviderResponseAndRecordsTokenUsage() {
    ModelChatRequest request = new ModelChatRequest("", "hello");
    ModelMetadata metadata =
        new ModelMetadata("deepseek", "model-a", new TokenUsage(11, 7, 18), false);
    when(providerClient.chat(request)).thenReturn(new ModelResponse("answer", metadata));

    ModelResponse response = gateway.chat(request);

    assertThat(response.content()).isEqualTo("answer");
    assertCounter("input", 11);
    assertCounter("output", 7);
    assertCounter("total", 18);
  }

  @Test
  void recordsStreamUsageOnlyFromTerminalChunk() {
    ModelChatRequest request = new ModelChatRequest("", "stream");
    ModelMetadata partial = new ModelMetadata("deepseek", "model-a", TokenUsage.unknown(), false);
    ModelMetadata terminal =
        new ModelMetadata("deepseek", "model-a", new TokenUsage(3, 4, 7), false);
    when(providerClient.streamChat(request))
        .thenReturn(
            Flux.just(
                new ModelStreamChunk("part", false, partial),
                new ModelStreamChunk("", true, terminal)));

    List<ModelStreamChunk> chunks = gateway.streamChat(request).collectList().block();

    assertThat(chunks).hasSize(2);
    assertCounter("input", 3);
    assertCounter("output", 4);
    assertCounter("total", 7);
  }

  private void assertCounter(String kind, double count) {
    assertThat(
            meterRegistry
                .get("tripagent.model.tokens")
                .tags("provider", "deepseek", "model", "model-a", "kind", kind)
                .counter()
                .count())
        .isEqualTo(count);
  }
}
