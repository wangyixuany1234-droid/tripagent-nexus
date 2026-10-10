package com.tripagent.nexus.application.security;

import com.tripagent.nexus.application.task.RedisRuntimeStateService;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.infrastructure.config.SecurityProperties;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class UserRequestRateLimitService {

  private final AuthenticatedActorService actors;
  private final RedisRuntimeStateService runtimeState;
  private final SecurityProperties properties;

  public UserRequestRateLimitService(
      AuthenticatedActorService actors,
      RedisRuntimeStateService runtimeState,
      SecurityProperties properties) {
    this.actors = actors;
    this.runtimeState = runtimeState;
    this.properties = properties;
  }

  public void enforcePlanSubmissionLimit(Authentication authentication) {
    if (!actors.isEnforced()) {
      return;
    }
    UUID userId = actors.requireUserId(authentication);
    if (!runtimeState.allow(
        "plan-submission:" + userId,
        properties.getPlanSubmissionLimitPerMinute(),
        Duration.ofMinutes(1))) {
      throw new ApiException(
          "PLAN_SUBMISSION_RATE_LIMITED",
          HttpStatus.TOO_MANY_REQUESTS,
          "Too many plan requests were submitted. Please try again later.");
    }
  }
}
