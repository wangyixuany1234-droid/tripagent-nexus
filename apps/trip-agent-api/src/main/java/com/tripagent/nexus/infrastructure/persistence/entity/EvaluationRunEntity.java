package com.tripagent.nexus.infrastructure.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripagent.nexus.domain.model.DomainEnums.EvaluationRunStatus;
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
@Table(name = "evaluation_run")
public class EvaluationRunEntity {

  @Id
  @Column(name = "run_id", nullable = false)
  private UUID runId;

  @Column(name = "task_id")
  private UUID taskId;

  @Column(name = "dataset_version", nullable = false, length = 64)
  private String datasetVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "selected_case_ids", nullable = false, columnDefinition = "jsonb")
  private JsonNode selectedCaseIds;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private EvaluationRunStatus status;

  @Column(name = "sample_size", nullable = false)
  private int sampleSize;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode metrics;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode environment;

  @Column(name = "trace_id", nullable = false, length = 32)
  private String traceId;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "entity_version", nullable = false)
  private long entityVersion;

  protected EvaluationRunEntity() {}

  public EvaluationRunEntity(
      UUID runId,
      String datasetVersion,
      JsonNode selectedCaseIds,
      JsonNode environment,
      String traceId,
      Instant now) {
    this.runId = runId;
    this.datasetVersion = datasetVersion;
    this.selectedCaseIds = selectedCaseIds;
    this.status = EvaluationRunStatus.QUEUED;
    this.sampleSize = 0;
    this.environment = environment;
    this.traceId = traceId;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void attachTask(UUID taskId, Instant now) {
    this.taskId = taskId;
    this.updatedAt = now;
  }

  public void start(int sampleSize, Instant now) {
    this.status = EvaluationRunStatus.RUNNING;
    this.sampleSize = sampleSize;
    this.startedAt = now;
    this.updatedAt = now;
  }

  public void complete(JsonNode metrics, Instant now) {
    this.status = EvaluationRunStatus.SUCCEEDED;
    this.metrics = metrics;
    this.completedAt = now;
    this.updatedAt = now;
  }

  public void fail(Instant now) {
    this.status = EvaluationRunStatus.FAILED;
    this.completedAt = now;
    this.updatedAt = now;
  }

  public void cancel(Instant now) {
    this.status = EvaluationRunStatus.CANCELLED;
    this.completedAt = now;
    this.updatedAt = now;
  }

  public UUID getRunId() {
    return runId;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public String getDatasetVersion() {
    return datasetVersion;
  }

  public EvaluationRunStatus getStatus() {
    return status;
  }

  public JsonNode getSelectedCaseIds() {
    return selectedCaseIds;
  }

  public int getSampleSize() {
    return sampleSize;
  }

  public JsonNode getMetrics() {
    return metrics;
  }

  public JsonNode getEnvironment() {
    return environment;
  }

  public String getTraceId() {
    return traceId;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
