package com.tripagent.nexus.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import com.tripagent.nexus.infrastructure.config.PlanningProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SystemControllerTest {

  private static final Instant NOW = Instant.parse("2026-09-06T08:00:00Z");

  @Test
  void reportsLiveWhenAllUserFacingProvidersAreLive() {
    SystemController controller =
        new SystemController(
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ModelGatewayProperties(),
            new PlanningProperties(),
            new MockEnvironment());

    SystemController.SystemHealth health = controller.get();

    assertThat(health.status()).isEqualTo("UP");
    assertThat(health.service()).isEqualTo("trip-agent-api");
    assertThat(health.apiVersion()).isEqualTo("v1");
    assertThat(health.providerMode()).isEqualTo("LIVE");
    assertThat(health.timestamp()).isEqualTo(NOW);
  }

  @Test
  void reportsMockWhenAnyUserFacingProviderIsMock() {
    ModelGatewayProperties modelProperties = new ModelGatewayProperties();
    PlanningProperties planningProperties = new PlanningProperties();
    planningProperties.setProviderMode(PlanningProperties.ProviderMode.MOCK);

    SystemController controller =
        new SystemController(
            Clock.fixed(NOW, ZoneOffset.UTC),
            modelProperties,
            planningProperties,
            new MockEnvironment());

    assertThat(controller.get().providerMode()).isEqualTo("MOCK");
  }

  @Test
  void reportsMockFromMcpModeWithoutRequiringMcpServerConfiguration() {
    SystemController controller =
        new SystemController(
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ModelGatewayProperties(),
            new PlanningProperties(),
            new MockEnvironment().withProperty("app.mcp.provider-mode", "MOCK"));

    assertThat(controller.get().providerMode()).isEqualTo("MOCK");
  }
}
