package com.tripagent.nexus.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.ai-retrieval")
public class AiServiceProperties {

  @NotNull private URI baseUrl;

  @NotBlank private String serviceToken;

  @NotNull private Duration documentTimeout = Duration.ofSeconds(90);

  public URI getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(URI baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getServiceToken() {
    return serviceToken;
  }

  public void setServiceToken(String serviceToken) {
    this.serviceToken = serviceToken;
  }

  public Duration getDocumentTimeout() {
    return documentTimeout;
  }

  public void setDocumentTimeout(Duration documentTimeout) {
    this.documentTimeout = documentTimeout;
  }
}
