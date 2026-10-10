package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.infrastructure.persistence.entity.UserPreferenceEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPreferenceRepository extends JpaRepository<UserPreferenceEntity, UUID> {

  List<UserPreferenceEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId);

  Optional<UserPreferenceEntity> findByPreferenceIdAndUserId(UUID preferenceId, UUID userId);
}
