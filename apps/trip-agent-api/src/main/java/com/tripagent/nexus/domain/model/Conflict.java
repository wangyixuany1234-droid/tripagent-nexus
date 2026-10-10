package com.tripagent.nexus.domain.model;

import com.tripagent.nexus.domain.model.DomainEnums.ConflictStatus;
import com.tripagent.nexus.domain.model.DomainEnums.ConflictType;
import com.tripagent.nexus.domain.model.DomainEnums.Severity;
import java.util.List;
import java.util.UUID;

public record Conflict(
    UUID conflictId,
    String code,
    ConflictType type,
    Severity severity,
    String message,
    String fieldPath,
    List<UUID> relatedItemIds,
    boolean autoFixable,
    ConflictStatus status) {}
