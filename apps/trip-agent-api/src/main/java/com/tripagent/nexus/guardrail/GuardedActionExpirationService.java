package com.tripagent.nexus.guardrail;

import com.tripagent.nexus.domain.model.DomainEnums.ActionAuditEvent;
import com.tripagent.nexus.domain.model.DomainEnums.GuardedActionStatus;
import com.tripagent.nexus.infrastructure.persistence.entity.GuardedActionAuditEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.GuardedActionEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.GuardedActionAuditRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.GuardedActionRepository;
import com.tripagent.nexus.observability.TraceContextProvider;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuardedActionExpirationService {

  private final GuardedActionRepository actionRepository;
  private final GuardedActionAuditRepository auditRepository;
  private final TraceContextProvider traceContextProvider;

  public GuardedActionExpirationService(
      GuardedActionRepository actionRepository,
      GuardedActionAuditRepository auditRepository,
      TraceContextProvider traceContextProvider) {
    this.actionRepository = actionRepository;
    this.auditRepository = auditRepository;
    this.traceContextProvider = traceContextProvider;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void expire(UUID actionId, Instant now) {
    GuardedActionEntity action = actionRepository.findById(actionId).orElse(null);
    if (action == null || !isExpirable(action, now)) {
      return;
    }
    action.expire(now);
    actionRepository.saveAndFlush(action);
    auditRepository.save(
        new GuardedActionAuditEntity(
            UUID.randomUUID(),
            action.getActionId(),
            action.getConfirmedBy() == null ? action.getProposedBy() : action.getConfirmedBy(),
            ActionAuditEvent.EXPIRED,
            "Action authorization expired.",
            traceContextProvider.currentOrNewTraceId(),
            now));
  }

  static boolean isExpirable(GuardedActionEntity action, Instant now) {
    return (action.getStatus() == GuardedActionStatus.PENDING_CONFIRMATION
            || action.getStatus() == GuardedActionStatus.CONFIRMED)
        && !action.getExpiresAt().isAfter(now);
  }
}
