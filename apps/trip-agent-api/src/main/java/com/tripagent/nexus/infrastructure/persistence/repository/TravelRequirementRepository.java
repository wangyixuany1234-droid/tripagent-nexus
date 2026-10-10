package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.infrastructure.persistence.entity.TravelRequirementEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelRequirementRepository extends JpaRepository<TravelRequirementEntity, UUID> {}
