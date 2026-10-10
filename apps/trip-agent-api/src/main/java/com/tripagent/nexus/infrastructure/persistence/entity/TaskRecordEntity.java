package com.tripagent.nexus.infrastructure.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "task_record")
public class TaskRecordEntity {

  @Id
  @Column(name = "task_id", nullable = false)
  private UUID taskId;

  @Enumerated(EnumType.STRING)
  @Column(name = "task_type", nullable = false, length = 32)
  private TaskType type;

  @Column(name = "resource_id", nullable = false)
  private UUID resourceId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private TaskStatus status;

  @Column(nullable = false, length = 100)
  private String stage;

  @Column(nullable = false)
  private int progress;

  @Column(length = 1000)
  private String message;

  @Column(name = "trace_id", nullable = false, length = 32)
  private String traceId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "result_resource_ids", nullable = false, columnDefinition = "jsonb")
  private JsonNode resultResourceIds;

  @Column(name = "failure_code", length = 100)
  private String failureCode;

  @Column(name = "failure_reason", length = 1000)
  private String failureReason;

  @Column(nullable = false)
  private boolean retryable;

  @Column(name = "recovery_attempts", nullable = false)
  private int recoveryAttempts;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "degraded_components", nullable = false, columnDefinition = "jsonb")
  private JsonNode degradedComponents;

  @Column(name = "heartbeat_at", nullable = false)
  private Instant heartbeatAt;

  @Column(name = "cancel_requested", nullable = false)
  private boolean cancelRequested;

  @Column(name = "retry_of_task_id")
  private UUID retryOfTaskId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "entity_version", nullable = false)
  private long entityVersion;

  protected TaskRecordEntity() {}

  public TaskRecordEntity(
      UUID taskId, TaskType type, UUID resourceId, String traceId, Instant now) {
    this(taskId, type, resourceId, traceId, null, now);
  }

  public TaskRecordEntity(
      UUID taskId,
      TaskType type,
      UUID resourceId,
      String traceId,
      UUID retryOfTaskId,
      Instant now) {
    this.taskId = taskId;
    this.type = type;
    this.resourceId = resourceId;
    this.status = TaskStatus.CREATED;
    this.stage = TaskStatus.CREATED.name();
    this.progress = 0;
    this.message = "Task created";
    this.traceId = traceId;
    this.resultResourceIds = JsonNodeFactory.instance.arrayNode();
    this.retryable = false;
    this.recoveryAttempts = 0;
    this.degradedComponents = JsonNodeFactory.instance.arrayNode();
    this.heartbeatAt = now;
    this.cancelRequested = false;
    this.retryOfTaskId = retryOfTaskId;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void transition(TaskStatus next, String stage, int progress, String message, Instant now) {
    if ((status == next && status.isTerminal())
        || (status != next && !status.canTransitionTo(next))) {
      throw new IllegalStateException("TASK_STATE_TRANSITION_INVALID");
    }
    if (progress < this.progress || progress > 100) {
      throw new IllegalArgumentException("TASK_PROGRESS_INVALID");
    }
    this.status = next;
    this.stage = stage;
    this.progress = progress;
    this.message = message;
    this.heartbeatAt = now;
    this.updatedAt = now;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public TaskType getType() {
    return type;
  }

  public UUID getResourceId() {
    return resourceId;
  }

  public TaskStatus getStatus() {
    return status;
  }

  public String getStage() {
    return stage;
  }

  public int getProgress() {
    return progress;
  }

  public String getMessage() {
    return message;
  }

  public String getTraceId() {
    return traceId;
  }

  public JsonNode getResultResourceIds() {
    return resultResourceIds;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public boolean isRetryable() {
    return retryable;
  }

  public int getRecoveryAttempts() {
    return recoveryAttempts;
  }

  public JsonNode getDegradedComponents() {
    return degradedComponents;
  }

  public Instant getHeartbeatAt() {
    return heartbeatAt;
  }

  public boolean isCancelRequested() {
    return cancelRequested;
  }

  public UUID getRetryOfTaskId() {
    return retryOfTaskId;
  }

  public void setResultResourceIds(JsonNode resultResourceIds, Instant now) {
    this.resultResourceIds = resultResourceIds;
    this.heartbeatAt = now;
    this.updatedAt = now;
  }

  public void setFailure(String code, String reason, boolean retryable, Instant now) {
    this.failureCode = code;
    this.failureReason = reason;
    this.retryable = retryable;
    this.heartbeatAt = now;
    this.updatedAt = now;
  }

  public void setDegradedComponents(JsonNode degradedComponents, Instant now) {
    this.degradedComponents = degradedComponents;
    this.heartbeatAt = now;
    this.updatedAt = now;
  }

  public void recordRecoveryAttempt(Instant now) {
    this.recoveryAttempts++;
    this.heartbeatAt = now;
    this.updatedAt = now;
  }

  public void requestCancellation(Instant now) {
    this.cancelRequested = true;
    this.heartbeatAt = now;
    this.updatedAt = now;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
