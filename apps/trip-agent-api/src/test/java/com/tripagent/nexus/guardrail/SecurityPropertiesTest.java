package com.tripagent.nexus.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import com.tripagent.nexus.infrastructure.config.SecurityProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecurityPropertiesTest {

  @Test
  void acceptsExplicitHttpOrigins() {
    SecurityProperties properties = new SecurityProperties();
    properties.setAllowedOrigins(List.of("http://localhost:5173", "https://travel.example"));

    assertThat(properties.isAllowedOriginsSafe()).isTrue();
  }

  @Test
  void rejectsWildcardsAndOriginsWithPaths() {
    SecurityProperties wildcard = new SecurityProperties();
    wildcard.setAllowedOrigins(List.of("*"));
    SecurityProperties path = new SecurityProperties();
    path.setAllowedOrigins(List.of("https://travel.example/app"));

    assertThat(wildcard.isAllowedOriginsSafe()).isFalse();
    assertThat(path.isAllowedOriginsSafe()).isFalse();
  }
}
