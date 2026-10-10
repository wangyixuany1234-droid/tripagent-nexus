package com.tripagent.nexus.controller;

import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import com.tripagent.nexus.infrastructure.config.PlanningProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
public class SystemController {

  private final Clock clock;
  private final ModelGatewayProperties modelProperties;
  private final PlanningProperties planningProperties;
  private final Environment environment;

  public SystemController(
      Clock clock,
      ModelGatewayProperties modelProperties,
      PlanningProperties planningProperties,
      Environment environment) {
    this.clock = clock;
    this.modelProperties = modelProperties;
    this.planningProperties = planningProperties;
    this.environment = environment;
  }

  @GetMapping
  public SystemHealth get() {
    return new SystemHealth("UP", "trip-agent-api", "v1", providerMode(), clock.instant());
  }

  private String providerMode() {
    boolean mock =
        modelProperties.getProvider() == ModelGatewayProperties.Provider.MOCK
            || planningProperties.getProviderMode() == PlanningProperties.ProviderMode.MOCK
            || "MOCK".equalsIgnoreCase(environment.getProperty("app.mcp.provider-mode", "LIVE"));
    return mock ? "MOCK" : "LIVE";
  }

  public record SystemHealth(
      String status, String service, String apiVersion, String providerMode, Instant timestamp) {}
}
