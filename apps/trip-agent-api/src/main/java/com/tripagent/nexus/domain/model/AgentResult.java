package com.tripagent.nexus.domain.model;

import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AgentResult(
    UUID agentResultId,
    UUID taskId,
    String agentType,
    TaskStatus status,
    Map<String, Object> result,
    List<UUID> evidenceIds,
    List<String> errors,
    Instant createdAt) {}
