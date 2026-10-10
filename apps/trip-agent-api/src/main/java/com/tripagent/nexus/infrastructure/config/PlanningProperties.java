package com.tripagent.nexus.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.planning")
public class PlanningProperties {

  private boolean enabled = true;

  @NotNull private ProviderMode providerMode = ProviderMode.LIVE;

  @NotNull private UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Min(256) @Max(200000) private int tokenBudget = 12000;

  @Min(2) @Max(5) private int candidateTarget = 2;

  @Min(0) @Max(1) private int maxAutoReplans = 1;

  @Min(1) @Max(180) private int defaultCommuteMinutes = 45;

  @NotNull private Duration checkpointRetention = Duration.ofHours(24);

  @Min(1) @Max(32) private int maxConcurrentWorkflows = 4;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public ProviderMode getProviderMode() {
    return providerMode;
  }

  public void setProviderMode(ProviderMode providerMode) {
    this.providerMode = providerMode;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public void setTenantId(UUID tenantId) {
    this.tenantId = tenantId;
  }

  public int getTokenBudget() {
    return tokenBudget;
  }

  public void setTokenBudget(int tokenBudget) {
    this.tokenBudget = tokenBudget;
  }

  public int getCandidateTarget() {
    return candidateTarget;
  }

  public void setCandidateTarget(int candidateTarget) {
    this.candidateTarget = candidateTarget;
  }

  public int getMaxAutoReplans() {
    return maxAutoReplans;
  }

  public void setMaxAutoReplans(int maxAutoReplans) {
    this.maxAutoReplans = maxAutoReplans;
  }

  public int getDefaultCommuteMinutes() {
    return defaultCommuteMinutes;
  }

  public void setDefaultCommuteMinutes(int defaultCommuteMinutes) {
    this.defaultCommuteMinutes = defaultCommuteMinutes;
  }

  public enum ProviderMode {
    LIVE,
    MOCK
  }

  public Duration getCheckpointRetention() {
    return checkpointRetention;
  }

  public int getMaxConcurrentWorkflows() {
    return maxConcurrentWorkflows;
  }

  public void setMaxConcurrentWorkflows(int value) {
    maxConcurrentWorkflows = value;
  }

  public void setCheckpointRetention(Duration value) {
    if (value.isNegative() || value.isZero() || value.compareTo(Duration.ofDays(7)) > 0) {
      throw new IllegalArgumentException("PLANNING_CHECKPOINT_RETENTION_INVALID");
    }
    checkpointRetention = value;
  }
}
