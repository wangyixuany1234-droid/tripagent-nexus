package com.tripagent.nexus.agent.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AgentCardFactory;
import com.tripagent.nexus.agent.AgentContracts.AgentPolicy;
import com.tripagent.nexus.agent.AgentContracts.AgentResultEnvelope;
import com.tripagent.nexus.agent.AgentContracts.RetryPolicy;
import com.tripagent.nexus.agent.AgentDefinition;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.AgentSchemaValidator;
import com.tripagent.nexus.agent.AgentStatus;
import com.tripagent.nexus.agent.ContextEnvelope;
import com.tripagent.nexus.agent.ContextEnvelopeReducer;
import com.tripagent.nexus.agent.TripAgent;
import com.tripagent.nexus.agent.research.ResearchAgentModels;
import com.tripagent.nexus.application.service.AgentExecutionService;
import com.tripagent.nexus.observability.TraceContextProvider;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;

class A2ADelegationServiceTest {

  @Test
  void returnsTimeoutWhenSubmittedTaskNeverReachesTerminalState() throws Exception {
    AgentRegistry registry = mock(AgentRegistry.class);
    ContextEnvelopeReducer reducer = mock(ContextEnvelopeReducer.class);
    AgentSchemaValidator schemaValidator = mock(AgentSchemaValidator.class);
    TraceContextProvider traceContextProvider = mock(TraceContextProvider.class);
    AgentExecutionService executionService = mock(AgentExecutionService.class);
    Tracer tracer = mock(Tracer.class);
    Span span = mock(Span.class);
    TraceContext traceContext = mock(TraceContext.class);
    Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
    DefaultRequestHandler requestHandler = mock(DefaultRequestHandler.class);
    TripAgent agent = mock(TripAgent.class);
    TaskStore taskStore = mock(TaskStore.class);
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    AgentDefinition<ResearchAgentModels.Input, ResearchAgentModels.Output> definition =
        new AgentDefinition<>(
            AgentName.RESEARCH,
            AgentCardFactory.create(AgentName.RESEARCH, "Slow test agent.", Set.of(), "1.0.0"),
            ResearchAgentModels.Input.class,
            ResearchAgentModels.Output.class,
            Set.of(),
            new AgentPolicy(
                Duration.ofMillis(50),
                new RetryPolicy(1, Duration.ZERO, Duration.ZERO),
                Set.of("A2A_AGENT_TIMEOUT"),
                true,
                "research-v1.0.0"));
    ResearchAgentModels.Input input =
        new ResearchAgentModels.Input(
            UUID.randomUUID(),
            ContextEnvelope.empty(1, 8000),
            List.of("Beijing"),
            List.of("Areas"));

    doReturn(definition).when(agent).definition();
    when(registry.require(AgentName.RESEARCH))
        .thenReturn(new AgentRegistry.Endpoint(agent, requestHandler, taskStore));
    when(reducer.select(AgentName.RESEARCH, input.context())).thenReturn(input.context());
    doReturn(input)
        .when(schemaValidator)
        .parseAndValidate(any(), eq(ResearchAgentModels.Input.class), anyString());
    when(tracer.nextSpan()).thenReturn(span);
    when(span.name(any(String.class))).thenReturn(span);
    when(span.tag(any(String.class), any(String.class))).thenReturn(span);
    when(span.start()).thenReturn(span);
    when(span.context()).thenReturn(traceContext);
    when(traceContext.traceId()).thenReturn("0123456789abcdef0123456789abcdef");
    when(tracer.withSpan(span)).thenReturn(spanInScope);
    when(traceContextProvider.currentOrNewTraceId()).thenReturn("fedcba9876543210fedcba9876543210");
    Task workingTask =
        Task.builder()
            .id(UUID.randomUUID().toString())
            .contextId(input.taskId().toString())
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .build();
    when(requestHandler.onMessageSend(any(), any())).thenReturn(workingTask);
    when(requestHandler.onGetTask(any(), any()))
        .thenAnswer(
            ignored -> {
              Thread.sleep(Duration.ofSeconds(5));
              return workingTask;
            });

    A2ADelegationService service =
        new A2ADelegationService(
            registry,
            reducer,
            schemaValidator,
            objectMapper,
            traceContextProvider,
            tracer,
            executionService,
            mock(com.tripagent.nexus.application.planning.AgentCheckpointService.class));
    Instant startedAt = Instant.now();
    try {
      AgentResultEnvelope<JsonNode> result =
          service.delegate(AgentName.RESEARCH, input, "tenant-a:user-1");

      assertThat(result.status()).isEqualTo(AgentStatus.TIMEOUT);
      assertThat(result.warnings())
          .extracting(warning -> warning.code())
          .containsExactly("A2A_AGENT_TIMEOUT");
      assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(1));
      verify(span).name("a2a.delegate");
      verify(span).tag("agent.name", AgentName.RESEARCH.cardName());
      verify(requestHandler).onGetTask(any(), any());
      verify(requestHandler).onCancelTask(any(), any());
    } finally {
      service.close();
    }
  }
}
