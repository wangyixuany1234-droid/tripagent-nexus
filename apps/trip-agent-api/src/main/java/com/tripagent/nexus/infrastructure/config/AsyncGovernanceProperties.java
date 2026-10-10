package com.tripagent.nexus.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.async-governance")
public class AsyncGovernanceProperties {

  private boolean enabled = true;
  private boolean redisEnabled = true;

  @NotBlank private String planRequestedTopic = "trip.plan.requested";
  @NotBlank private String documentParseRequestedTopic = "document.parse.requested";
  @NotBlank private String documentParseCompletedTopic = "document.parse.completed";
  @NotBlank private String retrievalRequestedTopic = "retrieval.requested";
  @NotBlank private String agentTaskRequestedTopic = "agent.task.requested";
  @NotBlank private String agentTaskCompletedTopic = "agent.task.completed";
  @NotBlank private String planReviewRequestedTopic = "plan.review.requested";
  @NotBlank private String planCompletedTopic = "plan.completed";
  @NotBlank private String taskFailedTopic = "task.failed";
  @NotBlank private String deadLetterTopic = "dead-letter";

  @Min(1024) @Max(1048576) private int maxEventBytes = 262144;

  @Min(1) @Max(20) private int maxAttempts = 5;

  @NotNull private Duration initialBackoff = Duration.ofSeconds(1);
  @NotNull private Duration maximumBackoff = Duration.ofSeconds(30);
  @NotNull private Duration publishTimeout = Duration.ofSeconds(5);
  @NotNull private Duration outboxPollInterval = Duration.ofMillis(500);
  @NotNull private Duration staleTaskThreshold = Duration.ofMinutes(5);
  @NotNull private Duration recoveryPollInterval = Duration.ofSeconds(30);

  @Min(0) @Max(5) private int maxRecoveryAttempts = 1;

  @NotNull private Duration externalCallTimeout = Duration.ofSeconds(90);

  @Min(1) @Max(128) private int externalMaxConcurrency = 8;

  @Min(1) @Max(1000) private int externalCircuitWindow = 10;

  @Min(1) @Max(1000) private int externalCircuitMinimumCalls = 5;

  @Min(1) @Max(100) private float externalCircuitFailureRate = 50;

  @NotNull private Duration externalCircuitOpenDuration = Duration.ofSeconds(10);

  @Min(1) @Max(100) private int externalCircuitHalfOpenCalls = 1;

  @Min(1) @Max(1000) private int operationsReplayLimitPerMinute = 10;

  @NotNull private Duration redisTaskTtl = Duration.ofHours(24);
  @NotNull private Duration redisSseTtl = Duration.ofHours(24);
  @NotNull private Duration redisSessionTtl = Duration.ofHours(8);
  @NotNull private Duration redisHotPlaceTtl = Duration.ofMinutes(30);
  @NotNull private Duration redisLockTtl = Duration.ofMinutes(2);
  private String operationsToken = "";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isRedisEnabled() {
    return redisEnabled;
  }

  public void setRedisEnabled(boolean redisEnabled) {
    this.redisEnabled = redisEnabled;
  }

  public String getPlanRequestedTopic() {
    return planRequestedTopic;
  }

  public void setPlanRequestedTopic(String planRequestedTopic) {
    this.planRequestedTopic = planRequestedTopic;
  }

  public String getDocumentParseRequestedTopic() {
    return documentParseRequestedTopic;
  }

  public void setDocumentParseRequestedTopic(String documentParseRequestedTopic) {
    this.documentParseRequestedTopic = documentParseRequestedTopic;
  }

  public String getDocumentParseCompletedTopic() {
    return documentParseCompletedTopic;
  }

  public void setDocumentParseCompletedTopic(String documentParseCompletedTopic) {
    this.documentParseCompletedTopic = documentParseCompletedTopic;
  }

  public String getRetrievalRequestedTopic() {
    return retrievalRequestedTopic;
  }

  public void setRetrievalRequestedTopic(String retrievalRequestedTopic) {
    this.retrievalRequestedTopic = retrievalRequestedTopic;
  }

  public String getAgentTaskRequestedTopic() {
    return agentTaskRequestedTopic;
  }

  public void setAgentTaskRequestedTopic(String agentTaskRequestedTopic) {
    this.agentTaskRequestedTopic = agentTaskRequestedTopic;
  }

  public String getAgentTaskCompletedTopic() {
    return agentTaskCompletedTopic;
  }

  public void setAgentTaskCompletedTopic(String agentTaskCompletedTopic) {
    this.agentTaskCompletedTopic = agentTaskCompletedTopic;
  }

  public String getPlanReviewRequestedTopic() {
    return planReviewRequestedTopic;
  }

  public void setPlanReviewRequestedTopic(String planReviewRequestedTopic) {
    this.planReviewRequestedTopic = planReviewRequestedTopic;
  }

  public String getPlanCompletedTopic() {
    return planCompletedTopic;
  }

  public void setPlanCompletedTopic(String planCompletedTopic) {
    this.planCompletedTopic = planCompletedTopic;
  }

  public String getTaskFailedTopic() {
    return taskFailedTopic;
  }

  public void setTaskFailedTopic(String taskFailedTopic) {
    this.taskFailedTopic = taskFailedTopic;
  }

  public String getDeadLetterTopic() {
    return deadLetterTopic;
  }

  public void setDeadLetterTopic(String deadLetterTopic) {
    this.deadLetterTopic = deadLetterTopic;
  }

  public int getMaxEventBytes() {
    return maxEventBytes;
  }

  public void setMaxEventBytes(int maxEventBytes) {
    this.maxEventBytes = maxEventBytes;
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

  public Duration getMaximumBackoff() {
    return maximumBackoff;
  }

  public void setMaximumBackoff(Duration maximumBackoff) {
    this.maximumBackoff = maximumBackoff;
  }

  public Duration getPublishTimeout() {
    return publishTimeout;
  }

  public void setPublishTimeout(Duration publishTimeout) {
    this.publishTimeout = publishTimeout;
  }

  public Duration getOutboxPollInterval() {
    return outboxPollInterval;
  }

  public void setOutboxPollInterval(Duration outboxPollInterval) {
    this.outboxPollInterval = outboxPollInterval;
  }

  public Duration getStaleTaskThreshold() {
    return staleTaskThreshold;
  }

  public void setStaleTaskThreshold(Duration staleTaskThreshold) {
    this.staleTaskThreshold = staleTaskThreshold;
  }

  public Duration getRecoveryPollInterval() {
    return recoveryPollInterval;
  }

  public void setRecoveryPollInterval(Duration recoveryPollInterval) {
    this.recoveryPollInterval = recoveryPollInterval;
  }

  public int getMaxRecoveryAttempts() {
    return maxRecoveryAttempts;
  }

  public void setMaxRecoveryAttempts(int maxRecoveryAttempts) {
    this.maxRecoveryAttempts = maxRecoveryAttempts;
  }

  public Duration getExternalCallTimeout() {
    return externalCallTimeout;
  }

  public void setExternalCallTimeout(Duration externalCallTimeout) {
    this.externalCallTimeout = externalCallTimeout;
  }

  public int getExternalMaxConcurrency() {
    return externalMaxConcurrency;
  }

  public void setExternalMaxConcurrency(int externalMaxConcurrency) {
    this.externalMaxConcurrency = externalMaxConcurrency;
  }

  public int getOperationsReplayLimitPerMinute() {
    return operationsReplayLimitPerMinute;
  }

  public int getExternalCircuitWindow() {
    return externalCircuitWindow;
  }

  public void setExternalCircuitWindow(int value) {
    externalCircuitWindow = value;
  }

  public int getExternalCircuitMinimumCalls() {
    return externalCircuitMinimumCalls;
  }

  public void setExternalCircuitMinimumCalls(int value) {
    externalCircuitMinimumCalls = value;
  }

  public float getExternalCircuitFailureRate() {
    return externalCircuitFailureRate;
  }

  public void setExternalCircuitFailureRate(float value) {
    externalCircuitFailureRate = value;
  }

  public Duration getExternalCircuitOpenDuration() {
    return externalCircuitOpenDuration;
  }

  public void setExternalCircuitOpenDuration(Duration value) {
    externalCircuitOpenDuration = value;
  }

  public int getExternalCircuitHalfOpenCalls() {
    return externalCircuitHalfOpenCalls;
  }

  public void setExternalCircuitHalfOpenCalls(int value) {
    externalCircuitHalfOpenCalls = value;
  }

  public void setOperationsReplayLimitPerMinute(int operationsReplayLimitPerMinute) {
    this.operationsReplayLimitPerMinute = operationsReplayLimitPerMinute;
  }

  public Duration getRedisTaskTtl() {
    return redisTaskTtl;
  }

  public void setRedisTaskTtl(Duration redisTaskTtl) {
    this.redisTaskTtl = redisTaskTtl;
  }

  public Duration getRedisSseTtl() {
    return redisSseTtl;
  }

  public void setRedisSseTtl(Duration redisSseTtl) {
    this.redisSseTtl = redisSseTtl;
  }

  public Duration getRedisSessionTtl() {
    return redisSessionTtl;
  }

  public void setRedisSessionTtl(Duration redisSessionTtl) {
    this.redisSessionTtl = redisSessionTtl;
  }

  public Duration getRedisHotPlaceTtl() {
    return redisHotPlaceTtl;
  }

  public void setRedisHotPlaceTtl(Duration redisHotPlaceTtl) {
    this.redisHotPlaceTtl = redisHotPlaceTtl;
  }

  public Duration getRedisLockTtl() {
    return redisLockTtl;
  }

  public void setRedisLockTtl(Duration redisLockTtl) {
    this.redisLockTtl = redisLockTtl;
  }

  public String getOperationsToken() {
    return operationsToken;
  }

  public void setOperationsToken(String operationsToken) {
    this.operationsToken = operationsToken == null ? "" : operationsToken;
  }
}
