package com.tripagent.nexus.infrastructure.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.idempotency")
public class IdempotencyProperties {

  @NotNull private Duration waitTimeout = Duration.ofSeconds(30);
  @NotNull private Duration initialRetryInterval = Duration.ofMillis(10);
  @NotNull private Duration maximumRetryInterval = Duration.ofMillis(250);

  public Duration getWaitTimeout() {
    return waitTimeout;
  }

  public void setWaitTimeout(Duration waitTimeout) {
    this.waitTimeout = waitTimeout;
  }

  public Duration getInitialRetryInterval() {
    return initialRetryInterval;
  }

  public void setInitialRetryInterval(Duration initialRetryInterval) {
    this.initialRetryInterval = initialRetryInterval;
  }

  public Duration getMaximumRetryInterval() {
    return maximumRetryInterval;
  }

  public void setMaximumRetryInterval(Duration maximumRetryInterval) {
    this.maximumRetryInterval = maximumRetryInterval;
  }

  @AssertTrue(message = "idempotency timing values must be positive and ordered") public boolean isTimingConfigurationValid() {
    return waitTimeout != null
        && !waitTimeout.isNegative()
        && !waitTimeout.isZero()
        && initialRetryInterval != null
        && !initialRetryInterval.isNegative()
        && !initialRetryInterval.isZero()
        && maximumRetryInterval != null
        && maximumRetryInterval.compareTo(initialRetryInterval) >= 0
        && waitTimeout.compareTo(initialRetryInterval) >= 0;
  }
}
