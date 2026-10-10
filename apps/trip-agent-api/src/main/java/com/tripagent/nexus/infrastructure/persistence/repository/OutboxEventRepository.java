package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.infrastructure.persistence.entity.OutboxEventEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

  Optional<OutboxEventEntity> findByTopicAndIdempotencyKey(String topic, String idempotencyKey);

  @Query(
      value =
          """
          SELECT *
          FROM event_outbox
          WHERE status = 'PENDING'
            AND next_attempt_at <= :now
          ORDER BY created_at
          LIMIT 50
          FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<OutboxEventEntity> lockPendingForPublication(@Param("now") Instant now);
}
