package com.tripagent.nexus.skill;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.infrastructure.persistence.entity.TaskRecordEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.AgentResultRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.DocumentRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TaskRecordRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelPlanRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpResourceAuthorizerTest {

  private final TaskRecordRepository tasks = mock(TaskRecordRepository.class);
  private final TravelPlanRepository plans = mock(TravelPlanRepository.class);
  private final DocumentRepository documents = mock(DocumentRepository.class);
  private final AgentResultRepository executions = mock(AgentResultRepository.class);
  private final McpResourceAuthorizer authorizer =
      new McpResourceAuthorizer(tasks, plans, documents, executions);
  private final UUID ownerTaskId = UUID.randomUUID();
  private final UUID planId = UUID.randomUUID();
  private final UUID tripId = UUID.randomUUID();
  private final McpCallContext context =
      new McpCallContext(AgentName.REQUIREMENT.cardName(), ownerTaskId);

  @BeforeEach
  void setUpOwner() {
    when(executions.existsByTaskIdAndAgentTypeAndStatus(
            ownerTaskId,
            context.agentName(),
            com.tripagent.nexus.domain.model.DomainEnums.TaskStatus.RUNNING))
        .thenReturn(true);
    when(tasks.findById(ownerTaskId))
        .thenReturn(
            Optional.of(
                new TaskRecordEntity(
                    ownerTaskId, TaskType.PLAN_GENERATION, planId, "0".repeat(32), Instant.EPOCH)));
    when(plans.findTripIdByPlanId(planId)).thenReturn(Optional.of(tripId));
  }

  @Test
  void allowsDocumentAndTripOwnedByExecutionTask() {
    UUID documentId = UUID.randomUUID();
    when(documents.existsByDocumentIdAndTripId(documentId, tripId)).thenReturn(true);

    assertThatCode(() -> authorizer.requireDocument(context, documentId))
        .doesNotThrowAnyException();
    assertThatCode(() -> authorizer.requireTrip(context, tripId)).doesNotThrowAnyException();
  }

  @Test
  void hidesMissingOrCrossTripResources() {
    UUID documentId = UUID.randomUUID();

    assertDenied(() -> authorizer.requireDocument(context, documentId));
    assertDenied(() -> authorizer.requireDocument(context, UUID.randomUUID()));
    assertDenied(() -> authorizer.requireTrip(context, UUID.randomUUID()));
  }

  @Test
  void rejectsMissingOrNonPlanningOwnerTask() {
    assertDenied(
        () ->
            authorizer.requireTrip(
                new McpCallContext(AgentName.COORDINATOR.cardName(), UUID.randomUUID()), tripId));
    UUID documentTask = UUID.randomUUID();
    when(tasks.findById(documentTask))
        .thenReturn(
            Optional.of(
                new TaskRecordEntity(
                    documentTask,
                    TaskType.DOCUMENT_PARSE,
                    UUID.randomUUID(),
                    "0".repeat(32),
                    Instant.EPOCH)));
    assertDenied(
        () ->
            authorizer.requireTrip(
                new McpCallContext(AgentName.COORDINATOR.cardName(), documentTask), tripId));
  }

  @Test
  void rejectsAgentWithoutRunningExecutionForTask() {
    assertDenied(
        () ->
            authorizer.requireTrip(
                new McpCallContext(AgentName.COORDINATOR.cardName(), ownerTaskId), tripId));
  }

  private static void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(McpToolAuthorizer.McpAuthorizationException.class)
        .hasMessage("MCP_RESOURCE_NOT_FOUND");
  }
}
