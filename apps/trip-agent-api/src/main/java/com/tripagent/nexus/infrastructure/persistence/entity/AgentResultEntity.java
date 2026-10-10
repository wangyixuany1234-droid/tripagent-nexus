package com.tripagent.nexus.infrastructure.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
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
@Table(name = "agent_result")
public class AgentResultEntity {

  @Id
  @Column(name = "agent_result_id", nullable = false)
  private UUID agentResultId;

  @Column(name = "task_id", nullable = false)
  private UUID taskId;

  @Column(name = "agent_type", nullable = false, length = 100)
  private String agentType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private TaskStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode result;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "evidence_ids", nullable = false, columnDefinition = "jsonb")
  private JsonNode evidenceIds;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode errors;

  @Column(name = "result_version", nullable = false)
  private long resultVersion;

  @Column(name = "source_event_id")
  private UUID sourceEventId;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "execution_sequence", insertable = false, updatable = false)
  private Long executionSequence;

  @Version
  @Column(name = "entity_version", nullable = false)
  private long entityVersion;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AgentResultEntity() {}

  public AgentResultEntity(UUID agentResultId, UUID taskId, String agentType, Instant createdAt) {
    this.agentResultId = agentResultId;
    this.taskId = taskId;
    this.agentType = agentType;
    this.status = TaskStatus.RUNNING;
    this.result = JsonNodeFactory.instance.objectNode();
    this.evidenceIds = JsonNodeFactory.instance.arrayNode();
    this.errors = JsonNodeFactory.instance.arrayNode();
    this.resultVersion = 0;
    this.createdAt = createdAt;
  }

  public boolean complete(
      long version,
      UUID sourceEventId,
      TaskStatus status,
      JsonNode result,
      JsonNode evidenceIds,
      JsonNode errors,
      Instant completedAt) {
    if (version <= 0) {
      throw new IllegalArgumentException("AGENT_RESULT_VERSION_INVALID");
    }
    if (version < resultVersion || (version == resultVersion && this.completedAt != null)) {
      return false;
    }
    if (!status.isTerminal()) {
      throw new IllegalArgumentException("AGENT_RESULT_STATUS_NOT_TERMINAL");
    }
    this.resultVersion = version;
    this.sourceEventId = sourceEventId;
    this.status = status;
    this.result = result;
    this.evidenceIds = evidenceIds;
    this.errors = errors;
    this.completedAt = completedAt;
    return true;
  }

  public UUID getAgentResultId() {
    return agentResultId;
  }

  public UUID getTaskId() {
    return taskId;
  }

  public String getAgentType() {
    return agentType;
  }

  public TaskStatus getStatus() {
    return status;
  }

  public JsonNode getResult() {
    return result;
  }

  public JsonNode getEvidenceIds() {
    return evidenceIds;
  }

  public JsonNode getErrors() {
    return errors;
  }

  public long getResultVersion() {
    return resultVersion;
  }

  public UUID getSourceEventId() {
    return sourceEventId;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public Long getExecutionSequence() {
    return executionSequence;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
