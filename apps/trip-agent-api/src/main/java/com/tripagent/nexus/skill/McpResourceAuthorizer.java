package com.tripagent.nexus.skill;

import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.infrastructure.persistence.entity.TaskRecordEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.AgentResultRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.DocumentRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TaskRecordRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelPlanRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class McpResourceAuthorizer {

  private final TaskRecordRepository tasks;
  private final TravelPlanRepository plans;
  private final DocumentRepository documents;
  private final AgentResultRepository executions;

  public McpResourceAuthorizer(
      TaskRecordRepository tasks,
      TravelPlanRepository plans,
      DocumentRepository documents,
      AgentResultRepository executions) {
    this.tasks = tasks;
    this.plans = plans;
    this.documents = documents;
    this.executions = executions;
  }

  @Transactional(readOnly = true)
  public void requireDocument(McpCallContext context, UUID documentId) {
    UUID tripId = ownerTrip(context);
    boolean allowed = documents.existsByDocumentIdAndTripId(documentId, tripId);
    if (!allowed) {
      throw denied();
    }
  }

  @Transactional(readOnly = true)
  public void requireTrip(McpCallContext context, UUID requestedTripId) {
    if (!ownerTrip(context).equals(requestedTripId)) {
      throw denied();
    }
  }

  private UUID ownerTrip(McpCallContext context) {
    if (context == null || context.ownerTaskId() == null) {
      throw denied();
    }
    if (context.agentName() == null
        || !executions.existsByTaskIdAndAgentTypeAndStatus(
            context.ownerTaskId(), context.agentName(), TaskStatus.RUNNING)) {
      throw denied();
    }
    TaskRecordEntity task =
        tasks.findById(context.ownerTaskId()).orElseThrow(McpResourceAuthorizer::denied);
    if (task.getType() != TaskType.PLAN_GENERATION && task.getType() != TaskType.PLAN_REPLAN) {
      throw denied();
    }
    return plans
        .findTripIdByPlanId(task.getResourceId())
        .orElseThrow(McpResourceAuthorizer::denied);
  }

  private static McpToolAuthorizer.McpAuthorizationException denied() {
    return new McpToolAuthorizer.McpAuthorizationException("MCP_RESOURCE_NOT_FOUND");
  }
}
