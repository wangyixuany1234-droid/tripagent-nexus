package com.tripagent.nexus;

import com.tripagent.nexus.infrastructure.config.AiServiceProperties;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import com.tripagent.nexus.infrastructure.config.DeveloperToolsProperties;
import com.tripagent.nexus.infrastructure.config.DocumentProcessingProperties;
import com.tripagent.nexus.infrastructure.config.GuardrailProperties;
import com.tripagent.nexus.infrastructure.config.IdempotencyProperties;
import com.tripagent.nexus.infrastructure.config.MapTileProperties;
import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import com.tripagent.nexus.infrastructure.config.PlanningProperties;
import com.tripagent.nexus.infrastructure.config.SecurityProperties;
import com.tripagent.nexus.infrastructure.config.UploadProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
  AiServiceProperties.class,
  AsyncGovernanceProperties.class,
  DeveloperToolsProperties.class,
  DocumentProcessingProperties.class,
  GuardrailProperties.class,
  IdempotencyProperties.class,
  MapTileProperties.class,
  ModelGatewayProperties.class,
  PlanningProperties.class,
  SecurityProperties.class,
  UploadProperties.class
})
public class TripAgentApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(TripAgentApiApplication.class, args);
  }
}
