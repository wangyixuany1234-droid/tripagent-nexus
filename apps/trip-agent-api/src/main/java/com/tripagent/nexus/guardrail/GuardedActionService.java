package com.tripagent.nexus.guardrail;

import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.controller.dto.ApiModels.ConfirmGuardedActionRequest;
import com.tripagent.nexus.controller.dto.ApiModels.CreateGuardedActionRequest;
import com.tripagent.nexus.controller.dto.ApiModels.RejectGuardedActionRequest;
import com.tripagent.nexus.domain.model.ActionAuditRecord;
import com.tripagent.nexus.domain.model.DomainEnums.ActionAuditEvent;
import com.tripagent.nexus.domain.model.DomainEnums.GuardedActionStatus;
import com.tripagent.nexus.domain.model.DomainEnums.GuardedActionType;
import com.tripagent.nexus.domain.model.GuardedAction;
import com.tripagent.nexus.infrastructure.persistence.entity.GuardedActionAuditEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.GuardedActionEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.GuardedActionAuditRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.GuardedActionRepository;
import com.tripagent.nexus.observability.TraceContextProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuardedActionService {

  private final GuardedActionRepository actionRepository;
  private final GuardedActionAuditRepository auditRepository;
  private final GuardedActionExpirationService expirationService;
  private final PromptInjectionGuard promptInjectionGuard;
  private final TraceContextProvider traceContextProvider;
  private final Clock clock;

  public GuardedActionService(
      GuardedActionRepository actionRepository,
      GuardedActionAuditRepository auditRepository,
      GuardedActionExpirationService expirationService,
      PromptInjectionGuard promptInjectionGuard,
      TraceContextProvider traceContextProvider,
      Clock clock) {
    this.actionRepository = actionRepository;
    this.auditRepository = auditRepository;
    this.expirationService = expirationService;
    this.promptInjectionGuard = promptInjectionGuard;
    this.traceContextProvider = traceContextProvider;
    this.clock = clock;
  }

  @Transactional
  public GuardedAction propose(CreateGuardedActionRequest request, String actorId) {
    promptInjectionGuard.requireSafe(request.riskDescription());
    Instant now = clock.instant();
    if (!request.expiresAt().isAfter(now)) {
      throw violation("ACTION_EXPIRY_INVALID", "Action expiry must be in the future.");
    }
    if (requiresPositiveCost(request.type()) && request.amount().signum() <= 0) {
      throw violation("ACTION_AMOUNT_INVALID", "A real-cost action must have a positive amount.");
    }
    GuardedActionEntity action =
        actionRepository.saveAndFlush(
            new GuardedActionEntity(
                UUID.randomUUID(),
                request.type(),
                request.resourceId(),
                request.riskDescription(),
                request.amount(),
                request.currency(),
                request.expiresAt(),
                actorId,
                now));
    audit(action, actorId, ActionAuditEvent.PROPOSED, "Candidate action created.", now);
    return toDomain(action);
  }

  @Transactional
  public GuardedAction get(UUID actionId, String actorId) {
    GuardedActionEntity action = requireOwned(actionId, actorId);
    expireIfNeeded(action, now());
    return toDomain(action);
  }

  @Transactional
  public GuardedAction confirm(UUID actionId, ConfirmGuardedActionRequest request, String actorId) {
    requireMatchingActionId(actionId, request.actionId());
    GuardedActionEntity action = requireOwned(actionId, actorId);
    Instant now = now();
    rejectExpired(action, now, "ACTION_NOT_PENDING", "The action confirmation window has expired.");
    requirePending(action);
    if (action.getEntityVersion() != request.expectedVersion()) {
      throw conflict("ACTION_VERSION_CONFLICT", "The action changed before confirmation.");
    }
    if (!request.acknowledgedRisk()) {
      throw violation("ACTION_RISK_NOT_ACKNOWLEDGED", "Risk acknowledgement is required.");
    }
    if (action.getAmount().compareTo(request.acknowledgedAmount()) != 0
        || !action.getCurrency().equals(request.currency())) {
      throw violation(
          "ACTION_COST_MISMATCH", "The acknowledged amount and currency must match exactly.");
    }
    action.confirm(actorId, now);
    actionRepository.saveAndFlush(action);
    audit(action, actorId, ActionAuditEvent.CONFIRMED, "Risk and exact cost acknowledged.", now);
    return toDomain(action);
  }

  @Transactional
  public GuardedAction reject(UUID actionId, RejectGuardedActionRequest request, String actorId) {
    requireMatchingActionId(actionId, request.actionId());
    promptInjectionGuard.requireSafe(request.reason());
    GuardedActionEntity action = requireOwned(actionId, actorId);
    Instant now = now();
    rejectExpired(action, now, "ACTION_NOT_PENDING", "The action confirmation window has expired.");
    requirePending(action);
    if (action.getEntityVersion() != request.expectedVersion()) {
      throw conflict("ACTION_VERSION_CONFLICT", "The action changed before rejection.");
    }
    action.reject(now);
    actionRepository.saveAndFlush(action);
    audit(action, actorId, ActionAuditEvent.REJECTED, request.reason(), now);
    return toDomain(action);
  }

  @Transactional
  public GuardedAction markExecuted(
      UUID actionId,
      GuardedActionType expectedType,
      UUID expectedResourceId,
      BigDecimal exactAmount,
      String currency) {
    GuardedActionEntity action =
        actionRepository
            .findById(actionId)
            .orElseThrow(
                () ->
                    new ApiException(
                        "ACTION_NOT_FOUND", HttpStatus.NOT_FOUND, "Action was not found."));
    Instant now = now();
    rejectExpired(
        action, now, "ACTION_EXECUTION_NOT_AUTHORIZED", "The action authorization has expired.");
    if (action.getStatus() != GuardedActionStatus.CONFIRMED
        || action.getType() != expectedType
        || !action.getResourceId().equals(expectedResourceId)
        || action.getAmount().compareTo(exactAmount) != 0
        || !action.getCurrency().equals(currency)) {
      throw conflict(
          "ACTION_EXECUTION_NOT_AUTHORIZED",
          "The action is not confirmed for the exact requested operation and cost.");
    }
    action.markExecuted(now);
    actionRepository.saveAndFlush(action);
    audit(
        action,
        action.getConfirmedBy(),
        ActionAuditEvent.EXECUTED,
        "Confirmed action executed by a transactional adapter.",
        now);
    return toDomain(action);
  }

  private void expireIfNeeded(GuardedActionEntity action, Instant now) {
    if (GuardedActionExpirationService.isExpirable(action, now)) {
      action.expire(now);
      actionRepository.saveAndFlush(action);
      audit(
          action,
          action.getProposedBy(),
          ActionAuditEvent.EXPIRED,
          "Confirmation window expired.",
          now);
    }
  }

  private void rejectExpired(
      GuardedActionEntity action, Instant now, String errorCode, String message) {
    if (GuardedActionExpirationService.isExpirable(action, now)) {
      expirationService.expire(action.getActionId(), now);
      throw conflict(errorCode, message);
    }
  }

  private void requirePending(GuardedActionEntity action) {
    if (action.getStatus() != GuardedActionStatus.PENDING_CONFIRMATION) {
      throw conflict(
          "ACTION_NOT_PENDING", "Only an unexpired pending action can be confirmed or rejected.");
    }
  }

  private GuardedActionEntity requireOwned(UUID actionId, String actorId) {
    return actionRepository
        .findById(actionId)
        .filter(action -> action.getProposedBy().equals(actorId))
        .orElseThrow(
            () ->
                new ApiException(
                    "ACTION_NOT_FOUND", HttpStatus.NOT_FOUND, "Action was not found."));
  }

  private void audit(
      GuardedActionEntity action,
      String actorId,
      ActionAuditEvent eventType,
      String reason,
      Instant now) {
    auditRepository.save(
        new GuardedActionAuditEntity(
            UUID.randomUUID(),
            action.getActionId(),
            actorId,
            eventType,
            reason,
            traceContextProvider.currentOrNewTraceId(),
            now));
  }

  private GuardedAction toDomain(GuardedActionEntity action) {
    List<ActionAuditRecord> auditTrail =
        auditRepository.findByActionIdOrderByOccurredAtAsc(action.getActionId()).stream()
            .map(
                audit ->
                    new ActionAuditRecord(
                        audit.getAuditId(),
                        audit.getActionId(),
                        audit.getActorId(),
                        audit.getEventType(),
                        audit.getReason(),
                        audit.getOccurredAt(),
                        audit.getTraceId()))
            .toList();
    return new GuardedAction(
        action.getActionId(),
        action.getType(),
        action.getResourceId(),
        action.getStatus(),
        action.getRiskDescription(),
        action.getAmount(),
        action.getCurrency(),
        action.getExpiresAt(),
        action.getProposedBy(),
        action.getConfirmedBy(),
        action.getEntityVersion(),
        auditTrail,
        action.getCreatedAt(),
        action.getUpdatedAt());
  }

  private Instant now() {
    return clock.instant();
  }

  private static boolean requiresPositiveCost(GuardedActionType type) {
    return type == GuardedActionType.PAYMENT || type == GuardedActionType.REAL_COST;
  }

  private static void requireMatchingActionId(UUID pathId, UUID bodyId) {
    if (!pathId.equals(bodyId)) {
      throw violation("ACTION_ID_MISMATCH", "Body actionId must match the path actionId.");
    }
  }

  private static ApiException violation(String code, String message) {
    return new ApiException(code, HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private static ApiException conflict(String code, String message) {
    return new ApiException(code, HttpStatus.CONFLICT, message);
  }
}
