package com.tripagent.nexus.controller;

import com.tripagent.nexus.domain.model.Phase14Models.AvailabilityStatus;
import com.tripagent.nexus.domain.model.Phase14Models.SystemCapability;
import com.tripagent.nexus.domain.model.Phase14Models.SystemDependency;
import com.tripagent.nexus.domain.model.Phase14Models.SystemStatus;
import com.tripagent.nexus.infrastructure.config.DeveloperToolsProperties;
import com.tripagent.nexus.infrastructure.config.MapTileProperties;
import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import com.tripagent.nexus.infrastructure.config.PlanningProperties;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system/status")
public class SystemStatusController {

  private final HealthEndpoint healthEndpoint;
  private final ModelGatewayProperties modelProperties;
  private final PlanningProperties planningProperties;
  private final MapTileProperties mapProperties;
  private final DeveloperToolsProperties developerToolsProperties;
  private final Environment environment;
  private final Clock clock;

  public SystemStatusController(
      HealthEndpoint healthEndpoint,
      ModelGatewayProperties modelProperties,
      PlanningProperties planningProperties,
      MapTileProperties mapProperties,
      DeveloperToolsProperties developerToolsProperties,
      Environment environment,
      Clock clock) {
    this.healthEndpoint = healthEndpoint;
    this.modelProperties = modelProperties;
    this.planningProperties = planningProperties;
    this.mapProperties = mapProperties;
    this.developerToolsProperties = developerToolsProperties;
    this.environment = environment;
    this.clock = clock;
  }

  @GetMapping
  public SystemStatus get() {
    HealthComponent health = healthEndpoint.health();
    AvailabilityStatus database = componentStatus(health, "db");
    AvailabilityStatus cache = componentStatus(health, "redis");
    AvailabilityStatus messaging = componentStatus(health, "kafka");
    AvailabilityStatus retrieval = componentStatus(health, "aiRetrieval");
    String providerMode = providerMode();
    AvailabilityStatus model =
        modelProperties.getProvider() == ModelGatewayProperties.Provider.NONE
            ? AvailabilityStatus.DISABLED
            : AvailabilityStatus.AVAILABLE;
    List<SystemDependency> dependencies =
        List.of(
            new SystemDependency("database", "数据存储", database),
            new SystemDependency("cache", "会话与任务状态", cache),
            new SystemDependency("messaging", "异步任务处理", messaging),
            new SystemDependency("retrieval", "旅行知识检索", retrieval));
    List<SystemCapability> capabilities =
        List.of(
            new SystemCapability("model", "智能规划", model),
            new SystemCapability(
                "map",
                "地图",
                mapProperties.isEnabled()
                    ? AvailabilityStatus.AVAILABLE
                    : AvailabilityStatus.DISABLED),
            new SystemCapability("weather", "天气信息", mcpStatus()),
            new SystemCapability("retrieval", "资料检索", retrieval));
    AvailabilityStatus overall =
        dependencies.stream().anyMatch(item -> item.status() == AvailabilityStatus.UNAVAILABLE)
            ? AvailabilityStatus.DEGRADED
            : AvailabilityStatus.AVAILABLE;
    return new SystemStatus(
        overall,
        providerMode,
        modelProperties.getProvider().name(),
        developerToolsProperties.isEnabled(),
        dependencies,
        capabilities,
        clock.instant());
  }

  private AvailabilityStatus mcpStatus() {
    return "DISABLED".equalsIgnoreCase(environment.getProperty("app.mcp.provider-mode"))
        ? AvailabilityStatus.DISABLED
        : AvailabilityStatus.AVAILABLE;
  }

  private String providerMode() {
    boolean mock =
        modelProperties.getProvider() == ModelGatewayProperties.Provider.MOCK
            || planningProperties.getProviderMode() == PlanningProperties.ProviderMode.MOCK
            || "MOCK".equalsIgnoreCase(environment.getProperty("app.mcp.provider-mode", "LIVE"));
    return mock ? "MOCK" : "LIVE";
  }

  private static AvailabilityStatus componentStatus(HealthComponent health, String key) {
    if (!(health instanceof CompositeHealth compositeHealth)) {
      return AvailabilityStatus.UNAVAILABLE;
    }
    HealthComponent component = compositeHealth.getComponents().get(key);
    if (component == null) {
      return AvailabilityStatus.DISABLED;
    }
    return "UP".equals(component.getStatus().getCode())
        ? AvailabilityStatus.AVAILABLE
        : AvailabilityStatus.UNAVAILABLE;
  }
}
