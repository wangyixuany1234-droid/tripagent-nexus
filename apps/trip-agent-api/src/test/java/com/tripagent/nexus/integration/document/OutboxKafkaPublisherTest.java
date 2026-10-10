package com.tripagent.nexus.integration.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.application.service.DeadLetterService;
import com.tripagent.nexus.domain.model.DomainEnums.EventDeliveryStatus;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.OutboxEventEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.OutboxEventRepository;
import com.tripagent.nexus.integration.messaging.EventEnvelope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class OutboxKafkaPublisherTest {

  private static final Instant NOW = Instant.parse("2026-09-11T01:00:00Z");

  @Mock private OutboxEventRepository repository;
  @Mock private KafkaTemplate<String, String> kafkaTemplate;
  @Mock private DeadLetterService deadLetterService;
  @Captor private ArgumentCaptor<ProducerRecord<String, String>> records;

  private OutboxKafkaPublisher publisher;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    AsyncGovernanceProperties properties = new AsyncGovernanceProperties();
    properties.setInitialBackoff(Duration.ofMillis(1));
    properties.setMaximumBackoff(Duration.ofMillis(2));
    properties.setPublishTimeout(Duration.ofMillis(50));
    objectMapper = new ObjectMapper().findAndRegisterModules();
    publisher =
        new OutboxKafkaPublisher(
            repository,
            kafkaTemplate,
            deadLetterService,
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void kafkaBrokerFailureKeepsOutboxPendingUntilTheSameEventIsPublished() {
    OutboxEventEntity event = event(UUID.randomUUID(), "stage6-kafka-retry-0001");
    when(repository.lockPendingForPublication(NOW))
        .thenReturn(List.of(event))
        .thenReturn(List.of(event));
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

    publisher.publishPending();
    assertThat(event.getStatus()).isEqualTo(EventDeliveryStatus.PENDING);
    assertThat(event.getAttempts()).isEqualTo(1);

    publisher.publishPending();
    assertThat(event.getStatus()).isEqualTo(EventDeliveryStatus.PUBLISHED);
    verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
  }

  @Test
  void databaseFailureAfterSendCausesSafeRedeliveryWithTheSameEventId() {
    UUID eventId = UUID.randomUUID();
    OutboxEventEntity firstAttempt = event(eventId, "stage6-post-send-db-failure-0001");
    OutboxEventEntity redelivery = event(eventId, "stage6-post-send-db-failure-0001");
    when(repository.lockPendingForPublication(NOW))
        .thenReturn(List.of(firstAttempt))
        .thenReturn(List.of(redelivery));
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    when(repository.save(firstAttempt))
        .thenThrow(new DataAccessResourceFailureException("synthetic database disconnect"));
    when(repository.save(redelivery)).thenReturn(redelivery);

    assertThatThrownBy(publisher::publishPending)
        .isInstanceOf(DataAccessResourceFailureException.class);
    publisher.publishPending();

    verify(kafkaTemplate, times(2)).send(records.capture());
    assertThat(records.getAllValues())
        .allSatisfy(
            record ->
                assertThat(
                        new String(
                            record.headers().lastHeader("eventId").value(),
                            java.nio.charset.StandardCharsets.US_ASCII))
                    .isEqualTo(eventId.toString()));
    assertThat(redelivery.getStatus()).isEqualTo(EventDeliveryStatus.PUBLISHED);
  }

  private OutboxEventEntity event(UUID eventId, String idempotencyKey) {
    UUID taskId = UUID.randomUUID();
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            "plan.completed",
            EventEnvelope.CURRENT_VERSION,
            NOW,
            taskId,
            taskId,
            UUID.randomUUID().toString().replace("-", ""),
            UUID.randomUUID(),
            taskId,
            idempotencyKey,
            objectMapper.createObjectNode().put("planId", UUID.randomUUID().toString()));
    return new OutboxEventEntity(
        eventId,
        taskId,
        "plan.completed",
        taskId.toString(),
        envelope.eventType(),
        envelope.schemaVersion(),
        envelope.correlationId(),
        envelope.causationId(),
        envelope.idempotencyKey(),
        objectMapper.valueToTree(envelope),
        envelope.traceId(),
        3,
        NOW);
  }
}
