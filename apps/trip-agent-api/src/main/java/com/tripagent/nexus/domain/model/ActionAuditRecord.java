package com.tripagent.nexus.domain.model;

import com.tripagent.nexus.domain.model.DomainEnums.ActionAuditEvent;
import java.time.Instant;
import java.util.UUID;

public record ActionAuditRecord(
    UUID auditId,
    UUID actionId,
    String actorId,
    ActionAuditEvent eventType,
    String reason,
    Instant occurredAt,
    String traceId) {}
