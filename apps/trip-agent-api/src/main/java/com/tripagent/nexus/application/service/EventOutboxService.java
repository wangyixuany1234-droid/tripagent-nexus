package com.tripagent.nexus.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.OutboxEventEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.OutboxEventRepository;
import com.tripagent.nexus.integration.messaging.EventEnvelope;
import com.tripagent.nexus.integration.messaging.EventPayloadPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventOutboxService {

  private final OutboxEventRepository repository;
  private final EventPayloadPolicy payloadPolicy;
  private final AsyncGovernanceProperties properties;
  private final ObjectMapper objectMapper;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  public EventOutboxService(
      OutboxEventRepository repository,
      EventPayloadPolicy payloadPolicy,
      AsyncGovernanceProperties properties,
      ObjectMapper objectMapper,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.repository = repository;
    this.payloadPolicy = payloadPolicy;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  @Transactional
  public UUID enqueue(String topic, String eventKey, UUID aggregateId, EventEnvelope envelope) {
    payloadPolicy.validate(envelope);
    String deduplicationKey = topic.length() + ":" + topic + ":" + envelope.idempotencyKey();
    jdbcTemplate.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", resultSet -> {}, deduplicationKey);
    UUID existingEventId =
        repository
            .findByTopicAndIdempotencyKey(topic, envelope.idempotencyKey())
            .map(OutboxEventEntity::getEventId)
            .orElse(null);
    if (existingEventId != null) {
      return existingEventId;
    }
    Instant now = clock.instant();
    repository.save(
        new OutboxEventEntity(
            envelope.eventId(),
            aggregateId,
            topic,
            eventKey,
            envelope.eventType(),
            envelope.schemaVersion(),
            envelope.correlationId(),
            envelope.causationId(),
            envelope.idempotencyKey(),
            objectMapper.valueToTree(envelope),
            envelope.traceId(),
            properties.getMaxAttempts(),
            now));
    return envelope.eventId();
  }
}
