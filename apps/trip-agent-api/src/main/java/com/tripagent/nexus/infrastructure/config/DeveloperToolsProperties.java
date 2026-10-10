package com.tripagent.nexus.infrastructure.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.developer-tools")
public class DeveloperToolsProperties {

  private boolean enabled;

  @NotNull private URI jaegerBaseUrl = URI.create("http://localhost:16686");

  @NotNull private Duration timeout = Duration.ofSeconds(3);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public URI getJaegerBaseUrl() {
    return jaegerBaseUrl;
  }

  public void setJaegerBaseUrl(URI jaegerBaseUrl) {
    this.jaegerBaseUrl = jaegerBaseUrl;
  }

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = timeout;
  }
}
