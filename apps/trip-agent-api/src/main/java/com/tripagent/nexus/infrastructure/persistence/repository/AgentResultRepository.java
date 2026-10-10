package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.infrastructure.persistence.entity.AgentResultEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentResultRepository extends JpaRepository<AgentResultEntity, UUID> {

  List<AgentResultEntity> findByTaskIdOrderByExecutionSequenceAsc(UUID taskId);

  boolean existsByTaskIdAndAgentTypeAndStatus(UUID taskId, String agentType, TaskStatus status);
}
