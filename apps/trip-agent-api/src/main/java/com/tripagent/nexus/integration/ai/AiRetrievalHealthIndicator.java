package com.tripagent.nexus.integration.ai;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component("aiRetrieval")
@ConditionalOnProperty(
    name = "app.health.ai-retrieval.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AiRetrievalHealthIndicator implements HealthIndicator {

  private final RestClient restClient;

  public AiRetrievalHealthIndicator(@Qualifier("aiRetrievalRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public Health health() {
    long startedAt = System.nanoTime();
    try {
      HttpStatusCode status =
          restClient.get().uri("/internal/v1/health").retrieve().toBodilessEntity().getStatusCode();
      return Health.up()
          .withDetail("statusCode", status.value())
          .withDetail("latencyMs", elapsedMillis(startedAt))
          .build();
    } catch (RestClientException exception) {
      return Health.down()
          .withDetail("error", exception.getClass().getSimpleName())
          .withDetail("latencyMs", elapsedMillis(startedAt))
          .build();
    }
  }

  private static long elapsedMillis(long startedAt) {
    return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
  }
}
