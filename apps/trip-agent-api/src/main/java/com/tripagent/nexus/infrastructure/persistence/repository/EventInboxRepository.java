package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.infrastructure.persistence.entity.EventInboxEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventInboxRepository extends JpaRepository<EventInboxEntity, UUID> {

  Optional<EventInboxEntity> findByConsumerNameAndEventId(String consumerName, UUID eventId);
}
