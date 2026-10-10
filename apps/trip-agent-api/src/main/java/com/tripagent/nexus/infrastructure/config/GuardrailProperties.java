package com.tripagent.nexus.infrastructure.config;

import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.guardrails")
public class GuardrailProperties {

  private boolean promptInjectionEnabled = true;

  @NotEmpty private List<String> allowedExternalHosts =
      new ArrayList<>(
          List.of(
              "localhost",
              "127.0.0.1",
              "ai-retrieval-service",
              "trip-agent-api",
              "api.deepseek.com",
              "tile.openstreetmap.org"));

  public boolean isPromptInjectionEnabled() {
    return promptInjectionEnabled;
  }

  public void setPromptInjectionEnabled(boolean promptInjectionEnabled) {
    this.promptInjectionEnabled = promptInjectionEnabled;
  }

  public List<String> getAllowedExternalHosts() {
    return List.copyOf(allowedExternalHosts);
  }

  public void setAllowedExternalHosts(List<String> allowedExternalHosts) {
    this.allowedExternalHosts = new ArrayList<>(allowedExternalHosts);
  }
}
