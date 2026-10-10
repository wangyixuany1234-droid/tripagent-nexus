package com.tripagent.nexus.integration.document;

import com.tripagent.nexus.application.service.DeadLetterService;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.OutboxEventEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.OutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnExpression(
    "${app.async-governance.enabled:true} or ${app.document-processing.messaging-enabled:true}")
public class OutboxKafkaPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxKafkaPublisher.class);

  private final OutboxEventRepository repository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final DeadLetterService deadLetterService;
  private final AsyncGovernanceProperties properties;
  private final Clock clock;

  public OutboxKafkaPublisher(
      OutboxEventRepository repository,
      KafkaTemplate<String, String> kafkaTemplate,
      DeadLetterService deadLetterService,
      AsyncGovernanceProperties properties,
      Clock clock) {
    this.repository = repository;
    this.kafkaTemplate = kafkaTemplate;
    this.deadLetterService = deadLetterService;
    this.properties = properties;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${app.async-governance.outbox-poll-interval:500ms}")
  @Transactional
  public void publishPending() {
    Instant now = clock.instant();
    List<OutboxEventEntity> events = repository.lockPendingForPublication(now);
    for (OutboxEventEntity event : events) {
      try {
        ProducerRecord<String, String> record =
            new ProducerRecord<>(
                event.getTopic(), event.getEventKey(), event.getPayload().toString());
        record
            .headers()
            .add("eventId", ascii(event.getEventId().toString()))
            .add("eventVersion", ascii(event.getEventVersion()))
            .add("correlationId", ascii(event.getCorrelationId().toString()))
            .add("causationId", ascii(event.getCausationId().toString()))
            .add(
                "traceparent", traceParent(event.getTraceId()).getBytes(StandardCharsets.US_ASCII));
        kafkaTemplate
            .send(record)
            .get(properties.getPublishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        event.markPublished(clock.instant());
      } catch (Exception exception) {
        Instant failedAt = clock.instant();
        if (event.getAttempts() + 1 >= event.getMaxAttempts()) {
          event.markDeadLettered(
              "EVENT_PUBLICATION_EXHAUSTED",
              "The event could not be published after all retry attempts.",
              failedAt);
          deadLetterService.captureOutbox(event, exception);
        } else {
          event.markAttemptFailed(
              "EVENT_PUBLICATION_RETRY",
              "Event publication will be retried.",
              failedAt.plus(backoff(event.getAttempts() + 1)),
              failedAt);
        }
        log.warn(
            "Event publication failed eventId={} topic={} attempt={} errorType={}",
            event.getEventId(),
            event.getTopic(),
            event.getAttempts(),
            exception.getClass().getSimpleName());
      }
      repository.save(event);
    }
  }

  private Duration backoff(int attempt) {
    long multiplier = 1L << Math.min(attempt - 1, 20);
    return Duration.ofMillis(
        Math.min(
            properties.getMaximumBackoff().toMillis(),
            properties.getInitialBackoff().toMillis() * multiplier));
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String traceParent(String traceId) {
    String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "00-" + traceId + "-" + spanId + "-01";
  }
}
