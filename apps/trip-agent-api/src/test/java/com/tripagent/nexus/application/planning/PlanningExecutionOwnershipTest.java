package com.tripagent.nexus.application.planning;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.a2a.A2ADelegationService;
import com.tripagent.nexus.application.service.TaskApplicationService;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.domain.model.TaskRecord;
import com.tripagent.nexus.infrastructure.config.PlanningProperties;
import com.tripagent.nexus.integration.messaging.AsyncOperationException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanningExecutionOwnershipTest {
  @Test
  void lostOwnerCannotFailAWorkflowThatMayHaveBeenResumedElsewhere() {
    var tasks = mock(TaskApplicationService.class);
    var versions = mock(PlanVersionStore.class);
    var checkpoints = mock(PlanningCheckpointService.class);
    var tracer = mock(Tracer.class);
    var span = mock(Span.class, RETURNS_SELF);
    when(tracer.nextSpan()).thenReturn(span);
    when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));
    var task = mock(TaskRecord.class);
    when(task.status()).thenReturn(TaskStatus.RUNNING);
    when(tasks.get(any())).thenReturn(task);
    var lock = mock(PlanningCheckpointService.ExecutionLock.class);
    doThrow(PlanningCheckpointService.invalid()).when(lock).requireValid();
    when(lock.isValid()).thenReturn(false);
    var workflow =
        new PlanningWorkflowService(
            versions,
            mock(PlanningKnowledgeService.class),
            mock(PlanningContextFactory.class),
            mock(A2ADelegationService.class),
            mock(PlanCandidateAssembler.class),
            mock(PlanRuleEngine.class),
            mock(PlanRepairService.class),
            mock(PlanLifecycleEventService.class),
            tasks,
            new PlanningProperties(),
            new ObjectMapper(),
            tracer,
            checkpoints);
    assertThatThrownBy(
            () ->
                workflow.executeLocked(
                    UUID.randomUUID(), UUID.randomUUID(), TaskType.PLAN_GENERATION, lock))
        .isInstanceOf(AsyncOperationException.class)
        .hasMessage("PLANNING_EXECUTION_LOST");
    verify(tasks, never()).fail(any(), any(), any(), anyBoolean());
    verifyNoInteractions(versions, checkpoints);
  }
}
