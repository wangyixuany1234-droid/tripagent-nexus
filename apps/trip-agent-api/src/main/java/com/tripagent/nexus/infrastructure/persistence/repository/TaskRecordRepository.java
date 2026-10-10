package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.infrastructure.persistence.entity.TaskRecordEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRecordRepository extends JpaRepository<TaskRecordEntity, UUID> {

  Optional<TaskRecordEntity> findFirstByTypeAndResourceIdOrderByCreatedAtDesc(
      TaskType type, UUID resourceId);

  List<TaskRecordEntity> findByResourceIdInOrderByUpdatedAtDesc(Collection<UUID> resourceIds);

  List<TaskRecordEntity> findTop100ByStatusInAndHeartbeatAtBeforeOrderByHeartbeatAtAsc(
      Collection<TaskStatus> statuses, Instant heartbeatBefore);
}
