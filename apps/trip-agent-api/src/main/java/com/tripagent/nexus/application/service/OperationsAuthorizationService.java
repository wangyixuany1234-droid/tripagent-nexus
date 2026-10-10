package com.tripagent.nexus.application.service;

import com.tripagent.nexus.application.task.RedisRuntimeStateService;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class OperationsAuthorizationService {

  private final AsyncGovernanceProperties properties;
  private final RedisRuntimeStateService runtimeState;

  public OperationsAuthorizationService(
      AsyncGovernanceProperties properties, RedisRuntimeStateService runtimeState) {
    this.properties = properties;
    this.runtimeState = runtimeState;
  }

  public void authorize(String providedToken) {
    String expected = properties.getOperationsToken();
    if (expected.length() < 32
        || providedToken == null
        || !MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            providedToken.getBytes(StandardCharsets.UTF_8))) {
      throw new ApiException(
          "OPERATIONS_UNAUTHORIZED",
          HttpStatus.UNAUTHORIZED,
          "A valid operations credential is required.");
    }
  }

  public void enforceReplayRateLimit() {
    if (!runtimeState.allow(
        "dead-letter-replay",
        properties.getOperationsReplayLimitPerMinute(),
        Duration.ofMinutes(1))) {
      throw new ApiException(
          "OPERATIONS_RATE_LIMITED",
          HttpStatus.TOO_MANY_REQUESTS,
          "The dead-letter replay rate limit has been reached.");
    }
  }
}
