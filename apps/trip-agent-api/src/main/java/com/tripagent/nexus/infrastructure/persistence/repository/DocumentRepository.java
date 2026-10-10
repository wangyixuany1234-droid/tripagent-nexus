package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.infrastructure.persistence.entity.DocumentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {
  Optional<DocumentEntity> findByTripIdAndSha256(UUID tripId, String sha256);

  List<DocumentEntity> findByTripId(UUID tripId);

  boolean existsByDocumentIdAndTripId(UUID documentId, UUID tripId);
}
