package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.infrastructure.persistence.entity.TripEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripRepository extends JpaRepository<TripEntity, UUID> {
  boolean existsByTripIdAndUserId(UUID tripId, UUID userId);

  List<TripEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId);
}
