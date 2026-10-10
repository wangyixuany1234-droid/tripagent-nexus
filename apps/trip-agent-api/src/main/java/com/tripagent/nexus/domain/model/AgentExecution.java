package com.tripagent.nexus.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentExecution(
    UUID agentExecutionId,
    UUID taskId,
    String agentName,
    Status status,
    String summary,
    int evidenceCount,
    List<Warning> warnings,
    Long durationMs,
    Instant startedAt,
    Instant completedAt) {

  public AgentExecution {
    warnings = List.copyOf(warnings);
  }

  public enum Status {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    DEGRADED,
    FAILED,
    TIMEOUT,
    SKIPPED
  }

  public record Warning(String code, String message) {}
}
