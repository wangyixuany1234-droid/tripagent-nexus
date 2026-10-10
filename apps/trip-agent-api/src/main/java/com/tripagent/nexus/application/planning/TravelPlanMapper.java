package com.tripagent.nexus.application.planning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.domain.model.BudgetSummary;
import com.tripagent.nexus.domain.model.Conflict;
import com.tripagent.nexus.domain.model.DayPlan;
import com.tripagent.nexus.domain.model.Evidence;
import com.tripagent.nexus.domain.model.ReplanInputSnapshot;
import com.tripagent.nexus.domain.model.TravelPlan;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelPlanEntity;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TravelPlanMapper {

  private static final TypeReference<List<DayPlan>> DAYS = new TypeReference<>() {};
  private static final TypeReference<List<Conflict>> CONFLICTS = new TypeReference<>() {};
  private static final TypeReference<List<Evidence>> EVIDENCE = new TypeReference<>() {};

  private final ObjectMapper objectMapper;

  public TravelPlanMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public TravelPlan toDomain(TravelPlanEntity entity) {
    return new TravelPlan(
        entity.getPlanId(),
        entity.getPlanFamilyId(),
        entity.getTripId(),
        entity.getPreviousPlanId(),
        entity.getAlternativeKey(),
        entity.getStrategy(),
        entity.getVersion(),
        entity.getTitle(),
        entity.getSummary(),
        objectMapper.convertValue(entity.getDays(), DAYS),
        objectMapper.convertValue(entity.getBudgetSummary(), BudgetSummary.class),
        objectMapper.convertValue(entity.getConflicts(), CONFLICTS),
        objectMapper.convertValue(entity.getEvidence(), EVIDENCE),
        entity.getTriggerReason(),
        objectMapper.convertValue(entity.getReplanInput(), ReplanInputSnapshot.class),
        entity.getChangeSummary(),
        entity.isAutoReplanned(),
        entity.getStatus(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
