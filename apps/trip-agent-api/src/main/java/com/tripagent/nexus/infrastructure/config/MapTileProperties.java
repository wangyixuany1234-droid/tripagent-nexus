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
@ConfigurationProperties(prefix = "app.maps")
public class MapTileProperties {

  private boolean enabled = true;

  @NotNull private URI tileBaseUrl = URI.create("https://tile.openstreetmap.org");

  @NotBlank private String userAgent = "TripAgent-Nexus/0.1";

  @NotNull private Duration connectTimeout = Duration.ofSeconds(3);

  @NotNull private Duration readTimeout = Duration.ofSeconds(8);

  @Min(0) @Max(19) private int maxZoom = 19;

  @Min(1024) private int maxTileBytes = 1_048_576;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public URI getTileBaseUrl() {
    return tileBaseUrl;
  }

  public void setTileBaseUrl(URI tileBaseUrl) {
    this.tileBaseUrl = tileBaseUrl;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getReadTimeout() {
    return readTimeout;
  }

  public void setReadTimeout(Duration readTimeout) {
    this.readTimeout = readTimeout;
  }

  public int getMaxZoom() {
    return maxZoom;
  }

  public void setMaxZoom(int maxZoom) {
    this.maxZoom = maxZoom;
  }

  public int getMaxTileBytes() {
    return maxTileBytes;
  }

  public void setMaxTileBytes(int maxTileBytes) {
    this.maxTileBytes = maxTileBytes;
  }
}
