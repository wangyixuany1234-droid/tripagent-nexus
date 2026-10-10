package com.tripagent.nexus.integration.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties.FallbackProvider;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

class ModelGatewayConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(ModelGatewayConfiguration.class, SupportConfiguration.class);

  @Test
  void createsMockGatewayOnlyWhenMockProfileIsExplicitlyActive() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=mock-model",
            "app.model.provider=mock",
            "app.model.model=test-model")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(ModelGateway.class)).isInstanceOf(MockModelGateway.class);
            });

    contextRunner
        .withPropertyValues("app.model.provider=none")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(MockModelGateway.class);
              assertThat(context).doesNotHaveBean(ModelGateway.class);
            });
  }

  @Test
  void forbidsMockFallbackInProduction() {
    ModelGatewayConfiguration configuration = new ModelGatewayConfiguration();
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod", "mock-model-fallback");
    configuration.setEnvironment(environment);
    ModelGatewayProperties properties = new ModelGatewayProperties();
    properties.setFallbackProvider(FallbackProvider.MOCK);
    properties.setAllowMockFallback(true);

    assertThatThrownBy(
            () ->
                configuration.selectedDeepseekModelGateway(
                    mock(DefaultModelGateway.class),
                    mockObjectProvider(),
                    properties,
                    new ModelExceptionClassifier()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Mock model fallback is forbidden in production.");
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<MockModelGateway> mockObjectProvider() {
    return mock(ObjectProvider.class);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ModelGatewayProperties.class)
  static class SupportConfiguration {

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    Validator validator() {
      return Validation.buildDefaultValidatorFactory().getValidator();
    }
  }
}
