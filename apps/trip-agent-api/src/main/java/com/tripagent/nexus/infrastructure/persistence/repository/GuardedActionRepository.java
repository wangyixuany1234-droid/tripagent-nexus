package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.infrastructure.persistence.entity.GuardedActionEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuardedActionRepository extends JpaRepository<GuardedActionEntity, UUID> {}
