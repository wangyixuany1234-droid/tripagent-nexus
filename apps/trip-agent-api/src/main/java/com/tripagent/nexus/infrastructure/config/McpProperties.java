package com.tripagent.nexus.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.mcp")
public class McpProperties {

  @NotNull private URI baseUrl = URI.create("http://localhost:8080");

  @NotBlank private String endpoint = "/mcp";

  @NotBlank private String serviceToken;

  @NotNull private Duration requestTimeout = Duration.ofSeconds(10);

  @Min(1024) @Max(4 * 1024 * 1024) private int maxRequestSize = 1024 * 1024;

  @NotNull private ProviderMode providerMode = ProviderMode.LIVE;

  @NotBlank private String expectedServerName = "tripagent-tools-mcp";

  @NotBlank private String expectedServerVersion = "1.0.0";

  public URI getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(URI baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public String getServiceToken() {
    return serviceToken;
  }

  public void setServiceToken(String serviceToken) {
    this.serviceToken = serviceToken;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public void setRequestTimeout(Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }

  public int getMaxRequestSize() {
    return maxRequestSize;
  }

  public void setMaxRequestSize(int maxRequestSize) {
    this.maxRequestSize = maxRequestSize;
  }

  public ProviderMode getProviderMode() {
    return providerMode;
  }

  public void setProviderMode(ProviderMode providerMode) {
    this.providerMode = providerMode;
  }

  public String getExpectedServerName() {
    return expectedServerName;
  }

  public void setExpectedServerName(String expectedServerName) {
    this.expectedServerName = expectedServerName;
  }

  public String getExpectedServerVersion() {
    return expectedServerVersion;
  }

  public void setExpectedServerVersion(String expectedServerVersion) {
    this.expectedServerVersion = expectedServerVersion;
  }

  public enum ProviderMode {
    LIVE,
    MOCK
  }
}
