package com.tripagent.nexus.agent.a2a;

import static com.tripagent.nexus.testsupport.ChaosEvidence.duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.*;
import com.tripagent.nexus.agent.AgentContracts.*;
import com.tripagent.nexus.agent.research.ResearchAgentModels;
import com.tripagent.nexus.application.service.AgentExecutionService;
import com.tripagent.nexus.observability.TraceContextProvider;
import com.tripagent.nexus.testsupport.ChaosEvidence;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class A2AChaosTest {
  @ParameterizedTest
  @ValueSource(strings = {"timeout", "failed", "impersonation"})
  void delegationFailsExplicitlyAndRecovers(String fault) throws Exception {
    ChaosEvidence.run(
        "a2a." + fault,
        "SDK_HANDLER_STUB",
        "Real A2A delegation policy with local SDK request-handler double",
        record -> {
          var registry = mock(AgentRegistry.class);
          var reducer = mock(ContextEnvelopeReducer.class);
          var schema = mock(AgentSchemaValidator.class);
          var traceProvider = mock(TraceContextProvider.class);
          var executions = mock(AgentExecutionService.class);
          var tracer = mock(Tracer.class);
          var span = mock(Span.class, RETURNS_SELF);
          var context = mock(TraceContext.class);
          var handler = mock(DefaultRequestHandler.class);
          var agent = mock(TripAgent.class);
          var mapper = new ObjectMapper().findAndRegisterModules();
          String traceId = UUID.randomUUID().toString().replace("-", "");
          when(tracer.nextSpan()).thenReturn(span);
          when(span.context()).thenReturn(context);
          when(context.traceId()).thenReturn(traceId);
          when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));
          when(traceProvider.currentOrNewTraceId()).thenReturn(traceId);
          when(executions.start(any(), any())).thenReturn(UUID.randomUUID());
          var input =
              new ResearchAgentModels.Input(
                  UUID.randomUUID(),
                  ContextEnvelope.empty(1, 8000),
                  List.of("Beijing"),
                  List.of("area"));
          var definition =
              new AgentDefinition<>(
                  AgentName.RESEARCH,
                  AgentCardFactory.create(AgentName.RESEARCH, "Synthetic", Set.of(), "1.0.0"),
                  ResearchAgentModels.Input.class,
                  ResearchAgentModels.Output.class,
                  Set.of(),
                  new AgentPolicy(
                      duration("callTimeoutMs"),
                      new RetryPolicy(1, Duration.ZERO, Duration.ZERO),
                      Set.of("A2A_AGENT_TIMEOUT"),
                      true,
                      "research-v1.0.0"));
          doReturn(definition).when(agent).definition();
          var endpoint = new AgentRegistry.Endpoint(agent, handler, mock(TaskStore.class));
          when(registry.require(AgentName.RESEARCH)).thenReturn(endpoint);
          when(reducer.select(AgentName.RESEARCH, input.context())).thenReturn(input.context());
          doReturn(input)
              .when(schema)
              .parseAndValidate(any(), eq(ResearchAgentModels.Input.class), anyString());
          var working =
              Task.builder()
                  .id(UUID.randomUUID().toString())
                  .contextId(input.taskId().toString())
                  .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                  .build();
          var completed = mock(Task.class);
          var artifact = mock(Artifact.class);
          var result =
              new AgentResultEnvelope<>(
                  UUID.fromString(working.id()),
                  AgentName.RESEARCH,
                  AgentStatus.SUCCEEDED,
                  mapper.createObjectNode().put("summary", "Synthetic"),
                  List.of(),
                  List.of(),
                  traceId);
          when(completed.status()).thenReturn(new TaskStatus(TaskState.TASK_STATE_COMPLETED));
          when(completed.id()).thenReturn(working.id());
          when(completed.contextId()).thenReturn(input.taskId().toString());
          when(completed.artifacts()).thenReturn(List.of(artifact));
          when(artifact.parts())
              .thenReturn(List.of(new DataPart(mapper.convertValue(result, Object.class))));
          doReturn(new ResearchAgentModels.Output("Synthetic", List.of(), true))
              .when(schema)
              .parseAndValidate(
                  any(), eq(ResearchAgentModels.Output.class), eq("A2A_RESULT_INVALID"));
          when(handler.onMessageSend(any(), any())).thenReturn(completed);
          var service =
              new A2ADelegationService(
                  registry,
                  reducer,
                  schema,
                  mapper,
                  traceProvider,
                  tracer,
                  executions,
                  mock(com.tripagent.nexus.application.planning.AgentCheckpointService.class));
          try {
            assertThat(service.delegate(AgentName.RESEARCH, input, "synthetic-tenant").status())
                .isEqualTo(AgentStatus.SUCCEEDED);
            clearInvocations(executions, handler);
            record.injected();
            switch (fault) {
              case "timeout" -> {
                when(handler.onMessageSend(any(), any())).thenReturn(working);
                when(handler.onGetTask(any(), any()))
                    .thenAnswer(
                        ignored -> {
                          Thread.sleep(duration("assertionTimeoutMs"));
                          return working;
                        });
              }
              case "failed" ->
                  when(handler.onMessageSend(any(), any()))
                      .thenReturn(
                          Task.builder()
                              .id(working.id())
                              .contextId(input.taskId().toString())
                              .status(new TaskStatus(TaskState.TASK_STATE_FAILED))
                              .build());
              case "impersonation" ->
                  when(registry.require(AgentName.RESEARCH))
                      .thenThrow(new IllegalArgumentException("Agent is not registered"));
              default -> throw new IllegalArgumentException(fault);
            }
            long start = System.nanoTime();
            if (fault.equals("impersonation")) {
              var emptyRegistry = new AgentRegistry(List.of());
              try {
                assertThatThrownBy(() -> emptyRegistry.require(AgentName.RESEARCH))
                    .isInstanceOf(IllegalArgumentException.class);
              } finally {
                emptyRegistry.close();
              }
              assertThatThrownBy(
                      () -> service.delegate(AgentName.RESEARCH, input, "synthetic-tenant"))
                  .isInstanceOf(IllegalArgumentException.class);
              verifyNoInteractions(executions, handler);
            } else {
              var failed = service.delegate(AgentName.RESEARCH, input, "synthetic-tenant");
              assertThat(failed.status())
                  .isEqualTo(fault.equals("timeout") ? AgentStatus.TIMEOUT : AgentStatus.FAILED);
              assertThat(failed.warnings())
                  .extracting(AgentWarning::code)
                  .contains(fault.equals("timeout") ? "A2A_AGENT_TIMEOUT" : "A2A_AGENT_FAILED");
              assertThat(Duration.ofNanos(System.nanoTime() - start))
                  .isLessThan(duration("assertionTimeoutMs"));
              verify(handler, times(1)).onMessageSend(any(), any());
              record.trace(
                  failed.traceId(), "Delegation trace correlation; span sink is a test double");
            }
            record.degraded(
                "Agent unavailable or rejected; no successful agent result is emitted.");
            doReturn(endpoint).when(registry).require(AgentName.RESEARCH);
            when(handler.onMessageSend(any(), any())).thenReturn(completed);
            record.cleared();
            assertThat(service.delegate(AgentName.RESEARCH, input, "synthetic-tenant").status())
                .isEqualTo(AgentStatus.SUCCEEDED);
            record.recovered();
          } finally {
            service.close();
          }
        });
  }
}
