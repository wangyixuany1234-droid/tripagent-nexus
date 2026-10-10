package com.tripagent.nexus.infrastructure.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "evaluation_case_result")
public class EvaluationCaseResultEntity {

  @Id
  @Column(name = "result_id", nullable = false)
  private UUID resultId;

  @Column(name = "run_id", nullable = false)
  private UUID runId;

  @Column(name = "case_id", nullable = false, length = 100)
  private String caseId;

  @Column(nullable = false, length = 64)
  private String category;

  @Column(nullable = false)
  private boolean passed;

  @Column(name = "latency_ms", nullable = false)
  private long latencyMs;

  @Column(name = "token_usage", nullable = false)
  private long tokenUsage;

  @Column(name = "failure_code", length = 100)
  private String failureCode;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "observed_codes", nullable = false, columnDefinition = "jsonb")
  private JsonNode observedCodes;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected EvaluationCaseResultEntity() {}

  public EvaluationCaseResultEntity(
      UUID resultId,
      UUID runId,
      String caseId,
      String category,
      boolean passed,
      long latencyMs,
      long tokenUsage,
      String failureCode,
      JsonNode observedCodes,
      Instant createdAt) {
    this.resultId = resultId;
    this.runId = runId;
    this.caseId = caseId;
    this.category = category;
    this.passed = passed;
    this.latencyMs = latencyMs;
    this.tokenUsage = tokenUsage;
    this.failureCode = failureCode;
    this.observedCodes = observedCodes;
    this.createdAt = createdAt;
  }

  public UUID getRunId() {
    return runId;
  }

  public String getCaseId() {
    return caseId;
  }

  public String getCategory() {
    return category;
  }

  public boolean isPassed() {
    return passed;
  }

  public long getLatencyMs() {
    return latencyMs;
  }

  public long getTokenUsage() {
    return tokenUsage;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public JsonNode getObservedCodes() {
    return observedCodes;
  }
}
