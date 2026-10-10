package com.tripagent.nexus.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripagent.nexus.agent.AgentContracts.AgentResultEnvelope;
import com.tripagent.nexus.agent.AgentContracts.AgentWarning;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.AgentStatus;
import com.tripagent.nexus.domain.model.AgentExecution;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.domain.model.TaskRecord;
import com.tripagent.nexus.infrastructure.persistence.entity.AgentResultEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.AgentResultRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TaskRecordRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentExecutionService {

  private static final TypeReference<List<AgentWarning>> WARNING_LIST = new TypeReference<>() {};

  private final AgentResultRepository repository;
  private final TaskRecordRepository taskRepository;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public AgentExecutionService(
      AgentResultRepository repository,
      TaskRecordRepository taskRepository,
      ObjectMapper objectMapper,
      Clock clock) {
    this.repository = repository;
    this.taskRepository = taskRepository;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UUID start(UUID taskId, AgentName agentName) {
    if (!taskRepository.existsById(taskId)) {
      return null;
    }
    AgentResultEntity execution =
        new AgentResultEntity(UUID.randomUUID(), taskId, agentName.cardName(), clock.instant());
    repository.saveAndFlush(execution);
    return execution.getAgentResultId();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID startInTransaction(UUID taskId, AgentName agentName) {
    return start(taskId, agentName);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void completeInTransaction(
      UUID executionId, AgentResultEnvelope<JsonNode> result, long durationMs) {
    completeVersioned(executionId, result, durationMs, 1, executionId);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void interruptInTransaction(UUID executionId) {
    fail(executionId, "AGENT_EXECUTION_INTERRUPTED", 0);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void complete(UUID executionId, AgentResultEnvelope<JsonNode> result, long durationMs) {
    completeVersioned(executionId, result, durationMs, 1, executionId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean completeVersioned(
      UUID executionId,
      AgentResultEnvelope<JsonNode> result,
      long durationMs,
      long resultVersion,
      UUID sourceEventId) {
    if (executionId == null) {
      return false;
    }
    AgentResultEntity execution = repository.findById(executionId).orElseThrow();
    Instant completedAt = clock.instant();
    ObjectNode projection = objectMapper.createObjectNode();
    projection.put("agentStatus", result.status().name());
    projection.put("summary", summary(result));
    projection.put("durationMs", Math.max(0, durationMs));
    projection.put("completedAt", completedAt.toString());
    boolean applied =
        execution.complete(
            resultVersion,
            sourceEventId,
            taskStatus(result.status()),
            projection,
            objectMapper.valueToTree(
                result.evidence().stream().map(evidence -> evidence.sourceId()).toList()),
            objectMapper.valueToTree(result.warnings()),
            completedAt);
    if (applied) {
      repository.save(execution);
    }
    return applied;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void fail(UUID executionId, String code, long durationMs) {
    if (executionId == null) {
      return;
    }
    AgentResultEntity execution = repository.findById(executionId).orElseThrow();
    Instant completedAt = clock.instant();
    ObjectNode projection = objectMapper.createObjectNode();
    projection.put("agentStatus", AgentStatus.FAILED.name());
    projection.put("summary", "Agent 执行失败");
    projection.put("durationMs", Math.max(0, durationMs));
    projection.put("completedAt", completedAt.toString());
    if (execution.complete(
        1,
        executionId,
        TaskStatus.FAILED,
        projection,
        objectMapper.createArrayNode(),
        objectMapper.valueToTree(List.of(new AgentWarning(code, "Agent 执行失败"))),
        completedAt)) {
      repository.save(execution);
    }
  }

  @Transactional(readOnly = true)
  public List<AgentExecution> list(TaskRecord task) {
    if (task.type() != TaskType.PLAN_GENERATION && task.type() != TaskType.PLAN_REPLAN) {
      return List.of();
    }
    Map<String, AgentResultEntity> latest = new LinkedHashMap<>();
    repository
        .findByTaskIdOrderByExecutionSequenceAsc(task.taskId())
        .forEach(execution -> latest.put(execution.getAgentType(), execution));
    return java.util.Arrays.stream(AgentName.values())
        .map(
            agentName -> {
              AgentResultEntity execution = latest.get(agentName.cardName());
              return execution == null ? pending(task, agentName) : toDomain(execution);
            })
        .toList();
  }

  private AgentExecution pending(TaskRecord task, AgentName agentName) {
    boolean terminal = task.status().isTerminal();
    return new AgentExecution(
        UUID.nameUUIDFromBytes(
            (task.taskId() + ":" + agentName.cardName()).getBytes(StandardCharsets.UTF_8)),
        task.taskId(),
        agentName.cardName(),
        terminal ? AgentExecution.Status.SKIPPED : AgentExecution.Status.QUEUED,
        terminal ? "任务结束前未执行" : "等待 Coordinator 调度",
        0,
        List.of(),
        null,
        null,
        null);
  }

  private AgentExecution toDomain(AgentResultEntity execution) {
    JsonNode projection = execution.getResult();
    Long durationMs =
        projection.has("durationMs") ? projection.path("durationMs").longValue() : null;
    Instant completedAt =
        projection.hasNonNull("completedAt")
            ? Instant.parse(projection.path("completedAt").textValue())
            : null;
    List<AgentWarning> storedWarnings =
        objectMapper.convertValue(execution.getErrors(), WARNING_LIST);
    List<AgentExecution.Warning> warnings =
        storedWarnings.stream()
            .map(warning -> new AgentExecution.Warning(warning.code(), warning.message()))
            .toList();
    return new AgentExecution(
        execution.getAgentResultId(),
        execution.getTaskId(),
        execution.getAgentType(),
        executionStatus(execution),
        projection.path("summary").asText("正在执行"),
        execution.getEvidenceIds().size(),
        warnings,
        durationMs,
        execution.getCreatedAt(),
        completedAt);
  }

  private static AgentExecution.Status executionStatus(AgentResultEntity execution) {
    String agentStatus = execution.getResult().path("agentStatus").asText();
    if (!agentStatus.isBlank()) {
      return AgentExecution.Status.valueOf(agentStatus);
    }
    return execution.getStatus() == TaskStatus.RUNNING
        ? AgentExecution.Status.RUNNING
        : AgentExecution.Status.FAILED;
  }

  private static TaskStatus taskStatus(AgentStatus status) {
    return switch (status) {
      case SUCCEEDED -> TaskStatus.SUCCEEDED;
      case DEGRADED -> TaskStatus.PARTIAL_SUCCESS;
      case SKIPPED -> TaskStatus.CANCELLED;
      case FAILED, TIMEOUT -> TaskStatus.FAILED;
    };
  }

  private static String summary(AgentResultEnvelope<JsonNode> result) {
    if (result.status() == AgentStatus.FAILED || result.status() == AgentStatus.TIMEOUT) {
      return result.warnings().isEmpty() ? "Agent 未返回可用结果" : result.warnings().getFirst().message();
    }
    int count =
        firstArraySize(
            result.result(),
            "options",
            "recommendations",
            "alternatives",
            "candidateAreas",
            "issues");
    String suffix = count > 0 ? "，产出 " + count + " 项结果" : "";
    return switch (result.agentName()) {
      case REQUIREMENT -> "旅行需求已完成结构化" + suffix;
      case RESEARCH -> "公共知识与用户记忆检索完成" + suffix;
      case TRANSPORT -> "交通候选生成完成" + suffix;
      case ACCOMMODATION -> "住宿候选生成完成" + suffix;
      case RECOMMENDATION -> "景点与餐饮候选生成完成" + suffix;
      case BUDGET -> "预算核算完成" + suffix;
      case REVIEW -> "行程审校完成" + suffix;
      case COORDINATOR -> "多 Agent 方案编排完成" + suffix;
    };
  }

  private static int firstArraySize(JsonNode node, String... fields) {
    for (String field : fields) {
      if (node.path(field).isArray()) {
        return node.path(field).size();
      }
    }
    return 0;
  }
}
