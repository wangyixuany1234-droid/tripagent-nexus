package com.tripagent.nexus.infrastructure.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.model")
public class ModelGatewayProperties {

  public static final String DEFAULT_MODEL = "deepseek-chat";

  @NotNull private Provider provider = Provider.DEEPSEEK;

  @NotBlank private String model = DEFAULT_MODEL;

  @NotNull private FallbackProvider fallbackProvider = FallbackProvider.NONE;

  private boolean allowMockFallback;

  @NotNull private Duration timeout = Duration.ofSeconds(15);

  @Min(1) private int maxAttempts = 3;

  @NotNull private Duration initialBackoff = Duration.ofMillis(200);

  @DecimalMin("1.0") private double backoffMultiplier = 2.0;

  @Min(1) private int maxConcurrency = 8;

  @Min(1) private int maxTotalCalls = Integer.MAX_VALUE;

  @Min(1024) private int maxResponseChars = 262144;

  @NotNull private Duration concurrencyWait = Duration.ofMillis(100);

  public Provider getProvider() {
    return provider;
  }

  public void setProvider(Provider provider) {
    this.provider = provider;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public FallbackProvider getFallbackProvider() {
    return fallbackProvider;
  }

  public void setFallbackProvider(FallbackProvider fallbackProvider) {
    this.fallbackProvider = fallbackProvider;
  }

  public boolean isAllowMockFallback() {
    return allowMockFallback;
  }

  public void setAllowMockFallback(boolean allowMockFallback) {
    this.allowMockFallback = allowMockFallback;
  }

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = timeout;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public Duration getInitialBackoff() {
    return initialBackoff;
  }

  public void setInitialBackoff(Duration initialBackoff) {
    this.initialBackoff = initialBackoff;
  }

  public double getBackoffMultiplier() {
    return backoffMultiplier;
  }

  public void setBackoffMultiplier(double backoffMultiplier) {
    this.backoffMultiplier = backoffMultiplier;
  }

  public int getMaxConcurrency() {
    return maxConcurrency;
  }

  public void setMaxConcurrency(int maxConcurrency) {
    this.maxConcurrency = maxConcurrency;
  }

  public int getMaxTotalCalls() {
    return maxTotalCalls;
  }

  public void setMaxTotalCalls(int maxTotalCalls) {
    this.maxTotalCalls = maxTotalCalls;
  }

  public Duration getConcurrencyWait() {
    return concurrencyWait;
  }

  public int getMaxResponseChars() {
    return maxResponseChars;
  }

  public void setMaxResponseChars(int value) {
    maxResponseChars = value;
  }

  public void setConcurrencyWait(Duration concurrencyWait) {
    this.concurrencyWait = concurrencyWait;
  }

  public enum Provider {
    DEEPSEEK,
    MOCK,
    NONE
  }

  public enum FallbackProvider {
    NONE,
    MOCK
  }
}
