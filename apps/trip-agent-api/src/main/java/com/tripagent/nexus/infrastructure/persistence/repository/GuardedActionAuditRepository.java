package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.infrastructure.persistence.entity.GuardedActionAuditEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuardedActionAuditRepository
    extends JpaRepository<GuardedActionAuditEntity, UUID> {

  List<GuardedActionAuditEntity> findByActionIdOrderByOccurredAtAsc(UUID actionId);
}
