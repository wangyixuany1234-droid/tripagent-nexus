package com.tripagent.nexus.domain.model;

import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Phase14Models {

  private Phase14Models() {}

  public enum PreferenceSource {
    USER,
    INFERRED
  }

  public enum PreferenceStatus {
    ACTIVE,
    REJECTED
  }

  public enum AvailabilityStatus {
    AVAILABLE,
    DEGRADED,
    UNAVAILABLE,
    DISABLED
  }

  public enum TraceSpanCategory {
    MODEL,
    RAG,
    AGENT,
    MCP,
    A2A,
    KAFKA
  }

  public record WorkbenchTask(
      UUID taskId,
      UUID tripId,
      UUID resultPlanId,
      String title,
      String destination,
      LocalDate startDate,
      LocalDate endDate,
      TaskType type,
      TaskStatus status,
      int progress,
      Instant createdAt,
      Instant updatedAt) {}

  public record UserPreference(
      UUID preferenceId,
      UUID userId,
      String category,
      String value,
      PreferenceSource source,
      PreferenceStatus status,
      Instant createdAt,
      Instant updatedAt) {}

  public record SystemDependency(String key, String label, AvailabilityStatus status) {}

  public record SystemCapability(String key, String label, AvailabilityStatus status) {}

  public record SystemStatus(
      AvailabilityStatus status,
      String providerMode,
      String modelProvider,
      boolean developerToolsEnabled,
      List<SystemDependency> dependencies,
      List<SystemCapability> capabilities,
      Instant checkedAt) {}

  public record TraceSpan(
      String spanId,
      String parentSpanId,
      TraceSpanCategory category,
      String name,
      String status,
      Instant startedAt,
      long durationMs,
      Map<String, String> attributes) {}

  public record DeveloperTrace(
      String traceId, String status, Instant startedAt, long durationMs, List<TraceSpan> spans) {}
}
