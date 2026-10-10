package com.tripagent.nexus.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.domain.model.DeadLetterRecord;
import com.tripagent.nexus.domain.model.DomainEnums.DeadLetterStatus;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.DeadLetterEventEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.OutboxEventEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.DeadLetterEventRepository;
import com.tripagent.nexus.integration.messaging.AsyncOperationException;
import com.tripagent.nexus.integration.messaging.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeadLetterService {

  private final DeadLetterEventRepository repository;
  private final EventOutboxService outboxService;
  private final TaskApplicationService taskService;
  private final AsyncGovernanceProperties properties;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public DeadLetterService(
      DeadLetterEventRepository repository,
      EventOutboxService outboxService,
      TaskApplicationService taskService,
      AsyncGovernanceProperties properties,
      ObjectMapper objectMapper,
      Clock clock) {
    this.repository = repository;
    this.outboxService = outboxService;
    this.taskService = taskService;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public void capture(ConsumerRecord<?, ?> record, Exception exception) {
    EventEnvelope original = parseOriginal(record);
    if (repository.findByOriginalEventId(original.eventId()).isPresent()) {
      return;
    }
    AsyncOperationException failure = findAsyncFailure(exception);
    String code = failure == null ? "EVENT_RETRIES_EXHAUSTED" : failure.code();
    String reason =
        failure == null
            ? "The asynchronous operation failed after all retry attempts."
            : failure.userMessage();
    Instant now = clock.instant();
    DeadLetterEventEntity deadLetter =
        new DeadLetterEventEntity(
            UUID.randomUUID(),
            original.eventId(),
            record.topic(),
            record.key() == null ? original.eventId().toString() : String.valueOf(record.key()),
            original.eventType(),
            original.schemaVersion(),
            original.correlationId(),
            original.causationId(),
            original.traceId(),
            original.tripId(),
            original.taskId(),
            objectMapper.valueToTree(original),
            code,
            reason,
            now);
    repository.save(deadLetter);
    if (original.taskId() != null) {
      taskService.fail(original.taskId(), code, reason, false);
    }
    if (!properties.getDeadLetterTopic().equals(record.topic())) {
      EventEnvelope deadLetterEvent =
          new EventEnvelope(
              UUID.randomUUID(),
              "dead-letter",
              EventEnvelope.CURRENT_VERSION,
              now,
              original.correlationId(),
              original.eventId(),
              original.traceId(),
              deadLetterReference(original.tripId(), original.eventId()),
              deadLetterReference(original.taskId(), original.eventId()),
              "dead-letter-" + original.eventId(),
              deadLetterPayload(deadLetter, now));
      outboxService.enqueue(
          properties.getDeadLetterTopic(),
          original.eventId().toString(),
          deadLetter.getDeadLetterId(),
          deadLetterEvent);
    }
  }

  @Transactional
  public void captureOutbox(OutboxEventEntity outbox, Exception exception) {
    EventEnvelope original = objectMapper.convertValue(outbox.getPayload(), EventEnvelope.class);
    if (repository.findByOriginalEventId(original.eventId()).isPresent()) {
      return;
    }
    String reason = "The event could not be published after all retry attempts.";
    Instant now = clock.instant();
    DeadLetterEventEntity deadLetter =
        new DeadLetterEventEntity(
            UUID.randomUUID(),
            original.eventId(),
            outbox.getTopic(),
            outbox.getEventKey(),
            original.eventType(),
            original.schemaVersion(),
            original.correlationId(),
            original.causationId(),
            original.traceId(),
            original.tripId(),
            original.taskId(),
            objectMapper.valueToTree(original),
            "EVENT_PUBLICATION_EXHAUSTED",
            reason,
            now);
    repository.save(deadLetter);
    if (original.taskId() != null) {
      taskService.fail(original.taskId(), "EVENT_PUBLICATION_EXHAUSTED", reason, false);
    }
    if (!properties.getDeadLetterTopic().equals(outbox.getTopic())) {
      EventEnvelope deadLetterEvent =
          new EventEnvelope(
              UUID.randomUUID(),
              "dead-letter",
              EventEnvelope.CURRENT_VERSION,
              now,
              original.correlationId(),
              original.eventId(),
              original.traceId(),
              deadLetterReference(original.tripId(), original.eventId()),
              deadLetterReference(original.taskId(), original.eventId()),
              "dead-letter-" + original.eventId(),
              deadLetterPayload(deadLetter, now));
      outboxService.enqueue(
          properties.getDeadLetterTopic(),
          original.eventId().toString(),
          deadLetter.getDeadLetterId(),
          deadLetterEvent);
    }
  }

  @Transactional(readOnly = true)
  public List<DeadLetterRecord> list(DeadLetterStatus status) {
    List<DeadLetterEventEntity> records =
        status == null
            ? repository.findTop200ByOrderByCreatedAtDesc()
            : repository.findTop200ByStatusOrderByCreatedAtDesc(status);
    return records.stream().map(DeadLetterService::toDomain).toList();
  }

  @Transactional
  public DeadLetterRecord replay(UUID deadLetterId, String reason) {
    DeadLetterEventEntity deadLetter =
        repository
            .findById(deadLetterId)
            .orElseThrow(
                () ->
                    new ApiException(
                        "DEAD_LETTER_NOT_FOUND",
                        HttpStatus.NOT_FOUND,
                        "Dead-letter event was not found."));
    if (deadLetter.getStatus() != DeadLetterStatus.PENDING) {
      throw new ApiException(
          "DEAD_LETTER_ALREADY_REPLAYED",
          HttpStatus.CONFLICT,
          "This dead-letter event has already been replayed.");
    }
    EventEnvelope original =
        objectMapper.convertValue(deadLetter.getOriginalEnvelope(), EventEnvelope.class);
    UUID replayEventId = UUID.randomUUID();
    UUID replayTaskId = replayTaskId(original);
    EventEnvelope replay =
        original.replay(replayEventId, original.eventId(), replayTaskId, clock.instant());
    outboxService.enqueue(
        deadLetter.getOriginalTopic(),
        deadLetter.getOriginalEventKey(),
        deadLetter.getDeadLetterId(),
        replay);
    deadLetter.markReplayed(replayEventId, replayTaskId, reason, clock.instant());
    return toDomain(repository.save(deadLetter));
  }

  private EventEnvelope parseOriginal(ConsumerRecord<?, ?> record) {
    try {
      return objectMapper.readValue(String.valueOf(record.value()), EventEnvelope.class);
    } catch (JsonProcessingException exception) {
      UUID eventId =
          UUID.nameUUIDFromBytes(
              (record.topic() + ":" + record.partition() + ":" + record.offset())
                  .getBytes(StandardCharsets.UTF_8));
      String traceId = eventId.toString().replace("-", "");
      ObjectNode payload = objectMapper.createObjectNode();
      payload.put("malformedRecord", true);
      return new EventEnvelope(
          eventId,
          "malformed.event",
          EventEnvelope.CURRENT_VERSION,
          clock.instant(),
          eventId,
          eventId,
          traceId,
          null,
          null,
          "malformed-event-" + eventId,
          payload);
    }
  }

  private ObjectNode deadLetterPayload(DeadLetterEventEntity deadLetter, Instant failedAt) {
    ObjectNode failure = objectMapper.createObjectNode();
    failure.put("code", deadLetter.getFailureCode());
    failure.put("message", deadLetter.getFailureReason());
    failure.put("retryable", false);
    failure.set("details", objectMapper.createArrayNode());

    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("originalEventId", deadLetter.getOriginalEventId().toString());
    payload.put("originalEventType", deadLetter.getOriginalEventType());
    payload.put("originalTopic", deadLetter.getOriginalTopic());
    payload.put("originalEventKey", deadLetter.getOriginalEventKey());
    payload.put("originalSchemaVersion", deadLetter.getEventVersion());
    payload.set("failure", failure);
    payload.put("exhaustedAttempts", properties.getMaxAttempts());
    payload.put("failedAt", failedAt.toString());
    return payload;
  }

  private static AsyncOperationException findAsyncFailure(Throwable exception) {
    Throwable current = exception;
    while (current != null) {
      if (current instanceof AsyncOperationException failure) {
        return failure;
      }
      current = current.getCause();
    }
    return null;
  }

  private static UUID deadLetterReference(UUID reference, UUID malformedEventId) {
    return reference == null ? malformedEventId : reference;
  }

  private static DeadLetterRecord toDomain(DeadLetterEventEntity entity) {
    return new DeadLetterRecord(
        entity.getDeadLetterId(),
        entity.getOriginalEventId(),
        entity.getOriginalTopic(),
        entity.getOriginalEventType(),
        entity.getEventVersion(),
        entity.getCorrelationId(),
        entity.getCausationId(),
        entity.getTraceId(),
        entity.getTaskId(),
        entity.getFailureCode(),
        entity.getFailureReason(),
        entity.getStatus(),
        entity.getReplayCount(),
        entity.getReplayEventId(),
        entity.getReplayTaskId(),
        entity.getReplayReason(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  private UUID replayTaskId(EventEnvelope original) {
    if (original.taskId() == null
        || (!"document.parse.requested".equals(original.eventType())
            && !"trip.plan.requested".equals(original.eventType()))) {
      return original.taskId();
    }
    return taskService.createRetry(original.taskId()).taskId();
  }
}
