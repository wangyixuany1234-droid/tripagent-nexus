package com.tripagent.nexus.application.service;

import com.tripagent.nexus.domain.model.DomainEnums.InboxStatus;
import com.tripagent.nexus.integration.messaging.EventEnvelope;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventInboxService {

  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  public EventInboxService(JdbcTemplate jdbcTemplate, Clock clock) {
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public InboxClaim claim(String consumerName, EventEnvelope event) {
    Instant now = clock.instant();
    int inserted =
        jdbcTemplate.update(
            """
            INSERT INTO event_inbox (
                inbox_id, consumer_name, event_id, event_type, event_version,
                correlation_id, causation_id, trace_id, task_id, status,
                attempts, received_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING', 1, ?, ?)
            ON CONFLICT (consumer_name, event_id) DO NOTHING
            """,
            UUID.randomUUID(),
            consumerName,
            event.eventId(),
            event.eventType(),
            event.schemaVersion(),
            event.correlationId(),
            event.causationId(),
            event.traceId(),
            event.taskId(),
            Timestamp.from(now),
            Timestamp.from(now));
    if (inserted == 1) {
      return InboxClaim.ACQUIRED;
    }
    int retried =
        jdbcTemplate.update(
            """
            UPDATE event_inbox
            SET status = 'PROCESSING', attempts = attempts + 1,
                last_error_code = NULL, updated_at = ?
            WHERE consumer_name = ? AND event_id = ? AND status = 'RETRYABLE'
            """,
            Timestamp.from(now),
            consumerName,
            event.eventId());
    return retried == 1 ? InboxClaim.ACQUIRED : InboxClaim.DUPLICATE;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void complete(String consumerName, UUID eventId) {
    Instant now = clock.instant();
    jdbcTemplate.update(
        """
        UPDATE event_inbox
        SET status = 'COMPLETED', completed_at = ?, updated_at = ?
        WHERE consumer_name = ? AND event_id = ? AND status = 'PROCESSING'
        """,
        Timestamp.from(now),
        Timestamp.from(now),
        consumerName,
        eventId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void fail(String consumerName, UUID eventId, String errorCode, boolean retryable) {
    jdbcTemplate.update(
        """
        UPDATE event_inbox
        SET status = ?, last_error_code = ?, updated_at = ?
        WHERE consumer_name = ? AND event_id = ? AND status = 'PROCESSING'
        """,
        retryable ? InboxStatus.RETRYABLE.name() : InboxStatus.FAILED.name(),
        errorCode,
        Timestamp.from(clock.instant()),
        consumerName,
        eventId);
  }

  public enum InboxClaim {
    ACQUIRED,
    DUPLICATE
  }
}
