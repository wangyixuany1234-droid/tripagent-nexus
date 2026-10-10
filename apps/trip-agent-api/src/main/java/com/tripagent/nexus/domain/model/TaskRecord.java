package com.tripagent.nexus.domain.model;

import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TaskRecord(
    UUID taskId,
    TaskType type,
    UUID resourceId,
    TaskStatus status,
    String stage,
    int progress,
    String message,
    String traceId,
    List<UUID> resultResourceIds,
    String failureCode,
    String failureReason,
    boolean retryable,
    int recoveryAttempts,
    List<String> degradedComponents,
    UUID retryOfTaskId,
    Instant createdAt,
    Instant updatedAt) {

  public TaskRecord {
    resultResourceIds = List.copyOf(resultResourceIds);
    degradedComponents = List.copyOf(degradedComponents);
  }
}
