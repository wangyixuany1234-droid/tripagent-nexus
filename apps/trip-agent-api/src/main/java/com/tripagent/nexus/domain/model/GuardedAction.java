package com.tripagent.nexus.domain.model;

import com.tripagent.nexus.domain.model.DomainEnums.GuardedActionStatus;
import com.tripagent.nexus.domain.model.DomainEnums.GuardedActionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GuardedAction(
    UUID actionId,
    GuardedActionType type,
    UUID resourceId,
    GuardedActionStatus status,
    String riskDescription,
    BigDecimal amount,
    String currency,
    Instant expiresAt,
    String proposedBy,
    String confirmedBy,
    long version,
    List<ActionAuditRecord> auditTrail,
    Instant createdAt,
    Instant updatedAt) {

  public GuardedAction {
    auditTrail = List.copyOf(auditTrail);
  }
}
