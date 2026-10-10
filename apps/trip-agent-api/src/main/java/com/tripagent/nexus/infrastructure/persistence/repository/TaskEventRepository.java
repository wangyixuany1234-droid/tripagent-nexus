package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.infrastructure.persistence.entity.TaskEventEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskEventRepository extends JpaRepository<TaskEventEntity, Long> {
  Optional<TaskEventEntity> findByTaskIdAndEventId(UUID taskId, UUID eventId);

  List<TaskEventEntity> findByTaskIdOrderBySequenceAsc(UUID taskId);

  List<TaskEventEntity> findByTaskIdAndSequenceGreaterThanOrderBySequenceAsc(
      UUID taskId, long sequence);
}
