package com.tripagent.nexus.integration.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties.FallbackProvider;
import com.tripagent.nexus.observability.TraceOperations;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
public class ModelGatewayConfiguration implements EnvironmentAware {

  private Environment environment;

  @Bean
  ModelExceptionClassifier modelExceptionClassifier() {
    return new ModelExceptionClassifier();
  }

  @Bean
  ModelCallExecutor modelCallExecutor(
      ModelGatewayProperties properties, ModelExceptionClassifier exceptionClassifier) {
    return new ModelCallExecutor(properties, exceptionClassifier);
  }

  @Bean
  StructuredOutputService structuredOutputService(
      ObjectMapper objectMapper,
      Validator validator,
      ModelGatewayProperties properties,
      ObjectProvider<TraceOperations> traceOperations) {
    return new StructuredOutputService(
        objectMapper,
        validator,
        traceOperations.getIfAvailable(),
        properties.getMaxResponseChars());
  }

  @Bean
  @Profile("!mock-model")
  @ConditionalOnProperty(
      name = "app.model.provider",
      havingValue = "deepseek",
      matchIfMissing = true)
  SpringAiDeepseekModelClient deepseekModelProviderClient(
      ChatClient.Builder chatClientBuilder, ModelGatewayProperties properties) {
    return new SpringAiDeepseekModelClient(chatClientBuilder, properties);
  }

  @Bean("deepseekModelGateway")
  @Profile("!mock-model")
  @ConditionalOnProperty(
      name = "app.model.provider",
      havingValue = "deepseek",
      matchIfMissing = true)
  DefaultModelGateway deepseekModelGateway(
      SpringAiDeepseekModelClient providerClient,
      ModelCallExecutor callExecutor,
      StructuredOutputService structuredOutputService,
      MeterRegistry meterRegistry,
      ObjectProvider<TraceOperations> traceOperations,
      ModelGatewayProperties properties) {
    return new DefaultModelGateway(
        providerClient,
        callExecutor,
        structuredOutputService,
        meterRegistry,
        traceOperations.getIfAvailable(),
        properties);
  }

  @Bean("mockModelGateway")
  @Profile({"mock-model", "mock-model-fallback"})
  MockModelGateway mockModelGateway(
      ModelGatewayProperties properties, ObjectProvider<TraceOperations> traceOperations) {
    return new MockModelGateway(properties, traceOperations.getIfAvailable());
  }

  @Bean
  @Primary
  @Profile("mock-model")
  ModelGateway selectedMockModelGateway(
      @Qualifier("mockModelGateway") MockModelGateway mockModelGateway) {
    return mockModelGateway;
  }

  @Bean
  @Primary
  @Profile("!mock-model")
  @ConditionalOnProperty(
      name = "app.model.provider",
      havingValue = "deepseek",
      matchIfMissing = true)
  ModelGateway selectedDeepseekModelGateway(
      @Qualifier("deepseekModelGateway") DefaultModelGateway deepseekModelGateway,
      @Qualifier("mockModelGateway") ObjectProvider<MockModelGateway> mockModelGateway,
      ModelGatewayProperties properties,
      ModelExceptionClassifier exceptionClassifier) {
    if (properties.getFallbackProvider() == FallbackProvider.NONE) {
      return deepseekModelGateway;
    }
    validateMockFallback(properties);
    MockModelGateway fallback =
        mockModelGateway.getIfAvailable(
            () -> {
              throw new IllegalStateException(
                  "Mock fallback requires the mock-model-fallback profile.");
            });
    return new FallbackModelGateway(deepseekModelGateway, fallback, exceptionClassifier);
  }

  private void validateMockFallback(ModelGatewayProperties properties) {
    if (environment.acceptsProfiles(Profiles.of("prod"))) {
      throw new IllegalStateException("Mock model fallback is forbidden in production.");
    }
    if (!properties.isAllowMockFallback()) {
      throw new IllegalStateException(
          "Mock model fallback requires app.model.allow-mock-fallback=true.");
    }
  }

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }
}
