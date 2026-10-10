package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.infrastructure.persistence.entity.ItineraryArtifactEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItineraryArtifactRepository extends JpaRepository<ItineraryArtifactEntity, UUID> {

  Optional<ItineraryArtifactEntity> findByIdempotencyKey(String idempotencyKey);
}
