package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.infrastructure.persistence.entity.EvaluationCaseResultEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationCaseResultRepository
    extends JpaRepository<EvaluationCaseResultEntity, UUID> {

  List<EvaluationCaseResultEntity> findByRunIdOrderByCaseIdAsc(UUID runId);
}
