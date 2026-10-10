package com.tripagent.nexus.infrastructure.health;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

@Component("kafka")
@ConditionalOnProperty(
    name = "app.health.kafka.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class KafkaHealthIndicator implements HealthIndicator {

  private static final Duration TIMEOUT = Duration.ofSeconds(3);

  private final KafkaAdmin kafkaAdmin;

  public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
    this.kafkaAdmin = kafkaAdmin;
  }

  @Override
  public Health health() {
    try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
      String clusterId =
          admin.describeCluster().clusterId().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      int nodeCount =
          admin.describeCluster().nodes().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).size();
      return Health.up().withDetail("clusterId", clusterId).withDetail("nodes", nodeCount).build();
    } catch (Exception exception) {
      return Health.down().withDetail("error", exception.getClass().getSimpleName()).build();
    }
  }
}
