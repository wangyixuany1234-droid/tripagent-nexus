package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.domain.model.DomainEnums.DeadLetterStatus;
import com.tripagent.nexus.infrastructure.persistence.entity.DeadLetterEventEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEventEntity, UUID> {

  Optional<DeadLetterEventEntity> findByOriginalEventId(UUID originalEventId);

  List<DeadLetterEventEntity> findTop200ByOrderByCreatedAtDesc();

  List<DeadLetterEventEntity> findTop200ByStatusOrderByCreatedAtDesc(DeadLetterStatus status);
}
