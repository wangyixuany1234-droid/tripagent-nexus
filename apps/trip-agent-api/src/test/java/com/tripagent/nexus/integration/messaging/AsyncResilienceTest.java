package com.tripagent.nexus.integration.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripagent.nexus.application.service.DeadLetterService;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import com.tripagent.nexus.integration.planning.PlanningEvents.PlanRequestedPayload;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

class AsyncResilienceTest {

  @Test
  void externalCallUsesBoundedExponentialRetry() {
    AsyncGovernanceProperties properties = new AsyncGovernanceProperties();
    properties.setInitialBackoff(Duration.ofMillis(1));
    properties.setMaximumBackoff(Duration.ofMillis(2));
    properties.setExternalCallTimeout(Duration.ofSeconds(1));
    ExternalCallExecutor executor = new ExternalCallExecutor(properties);
    AtomicInteger attempts = new AtomicInteger();
    try {
      String result =
          executor.execute(
              "temporary-fastapi",
              3,
              () -> {
                if (attempts.incrementAndGet() < 3) {
                  throw new AsyncOperationException(
                      "FASTAPI_TEMPORARILY_UNAVAILABLE",
                      true,
                      "The internal AI service is temporarily unavailable.");
                }
                return "recovered";
              });

      assertThat(result).isEqualTo("recovered");
      assertThat(attempts).hasValue(3);
    } finally {
      executor.close();
    }
  }

  @Test
  void eventPolicyRejectsRawImagesAndFullPrompts() {
    AsyncGovernanceProperties properties = new AsyncGovernanceProperties();
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    EventPayloadPolicy policy = new EventPayloadPolicy(objectMapper, properties);
    UUID taskId = UUID.randomUUID();
    EventEnvelope rawImage =
        new EventEnvelope(
            UUID.randomUUID(),
            "agent.task.requested",
            "1.0.0",
            Instant.now(),
            taskId,
            taskId,
            UUID.randomUUID().toString().replace("-", ""),
            UUID.randomUUID(),
            taskId,
            "event-policy-key-0001",
            objectMapper.createObjectNode().put("rawImage", "base64-content"));
    EventEnvelope prompt =
        new EventEnvelope(
            UUID.randomUUID(),
            "agent.task.requested",
            "1.0.0",
            Instant.now(),
            taskId,
            taskId,
            UUID.randomUUID().toString().replace("-", ""),
            UUID.randomUUID(),
            taskId,
            "event-policy-key-0002",
            objectMapper.createObjectNode().put("systemPrompt", "private prompt"));

    assertThatThrownBy(() -> policy.validate(rawImage))
        .isInstanceOf(AsyncOperationException.class)
        .hasMessage("EVENT_SENSITIVE_FIELD_FORBIDDEN");
    assertThatThrownBy(() -> policy.validate(prompt))
        .isInstanceOf(AsyncOperationException.class)
        .hasMessage("EVENT_SENSITIVE_FIELD_FORBIDDEN");
  }

  @Test
  void oldConsumerIgnoresNewOptionalEnvelopeAndPayloadFields() throws Exception {
    ObjectMapper objectMapper =
        new ObjectMapper()
            .findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    UUID taskId = UUID.randomUUID();
    PlanRequestedPayload payload =
        new PlanRequestedPayload(
            UUID.randomUUID(),
            TaskType.PLAN_GENERATION,
            "CREATE",
            1,
            1,
            2,
            List.of("BALANCED"),
            null,
            Instant.parse("2026-10-01T00:00:00Z"));
    EventEnvelope event =
        new EventEnvelope(
            UUID.randomUUID(),
            "trip.plan.requested",
            EventEnvelope.CURRENT_VERSION,
            Instant.parse("2026-10-01T00:00:00Z"),
            taskId,
            taskId,
            "0123456789abcdef0123456789abcdef",
            UUID.randomUUID(),
            taskId,
            "stage3-forward-compatible-0001",
            objectMapper.valueToTree(payload));
    ObjectNode encoded = objectMapper.valueToTree(event);
    encoded.put("futureEnvelopeField", "ignored-by-v1");
    ((ObjectNode) encoded.path("payload")).put("futurePayloadField", "ignored-by-v1");

    EventEnvelope decoded = objectMapper.treeToValue(encoded, EventEnvelope.class);
    PlanRequestedPayload decodedPayload =
        objectMapper.treeToValue(decoded.payload(), PlanRequestedPayload.class);

    assertThat(decoded.eventId()).isEqualTo(event.eventId());
    assertThat(decodedPayload.planId()).isEqualTo(payload.planId());
  }

  @Test
  void unknownEventVersionIsSentToIsolationWithoutRetry() {
    AsyncGovernanceProperties properties = new AsyncGovernanceProperties();
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    EventPayloadPolicy policy = new EventPayloadPolicy(objectMapper, properties);
    UUID eventId = UUID.randomUUID();
    EventEnvelope event =
        new EventEnvelope(
            eventId,
            "trip.plan.requested",
            "99.0.0",
            Instant.parse("2026-10-01T00:00:00Z"),
            eventId,
            eventId,
            "0123456789abcdef0123456789abcdef",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "stage3-unsupported-version-0001",
            objectMapper.createObjectNode());
    AsyncOperationException failure =
        catchThrowableOfType(() -> policy.validate(event), AsyncOperationException.class);
    DeadLetterService deadLetterService = mock(DeadLetterService.class);
    DefaultErrorHandler errorHandler =
        new KafkaGovernanceConfiguration().governedKafkaErrorHandler(deadLetterService, properties);
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>("trip.plan.requested", 0, 1L, eventId.toString(), "{}");

    boolean recovered =
        errorHandler.handleOne(
            failure, record, mock(Consumer.class), mock(MessageListenerContainer.class));

    assertThat(failure.code()).isEqualTo("EVENT_VERSION_UNSUPPORTED");
    assertThat(failure.retryable()).isFalse();
    assertThat(recovered).isTrue();
    verify(deadLetterService).capture(record, failure);
  }
}
