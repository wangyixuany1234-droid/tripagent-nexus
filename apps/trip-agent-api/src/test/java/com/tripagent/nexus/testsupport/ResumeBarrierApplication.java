package com.tripagent.nexus.testsupport;

import com.tripagent.nexus.TripAgentApiApplication;
import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import com.tripagent.nexus.integration.model.MockModelGateway;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.StructuredModelResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

public final class ResumeBarrierApplication {
  public static void main(String[] args) {
    new SpringApplication(TripAgentApiApplication.class, BarrierConfiguration.class).run(args);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class BarrierConfiguration {
    @Bean
    static BeanPostProcessor barrierModel(Environment environment) {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          if (!name.equals("selectedMockModelGateway")) return bean;
          return new MockModelGateway(new ModelGatewayProperties()) {
            @Override
            public <T> StructuredModelResponse<T> structuredChat(
                ModelChatRequest request, Class<T> type) {
              String agent = type.getEnclosingClass().getSimpleName().replace("Models", "");
              Path directory =
                  Path.of(environment.getRequiredProperty("qa.resume.audit-directory"));
              try {
                Files.createDirectories(directory);
                Files.writeString(
                    directory.resolve(agent + ".calls"),
                    "invoked\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
                if (agent.equals(environment.getProperty("qa.resume.block-agent", ""))) {
                  Files.writeString(directory.resolve("blocked"), agent);
                  if (!new CountDownLatch(1)
                      .await(
                          ChaosEvidence.value("applicationStartupTimeoutMs"),
                          TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("SYNTHETIC_BARRIER_DEADLINE");
                  }
                }
              } catch (java.io.IOException | InterruptedException failure) {
                throw new IllegalStateException("SYNTHETIC_BARRIER_FAILED", failure);
              }
              return super.structuredChat(request, type);
            }
          };
        }
      };
    }
  }
}
