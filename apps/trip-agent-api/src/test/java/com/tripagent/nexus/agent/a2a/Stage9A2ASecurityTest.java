package com.tripagent.nexus.agent.a2a;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripagent.nexus.agent.*;
import com.tripagent.nexus.agent.AgentContracts.*;
import com.tripagent.nexus.agent.research.ResearchAgentModels;
import com.tripagent.nexus.application.planning.AgentCheckpointService;
import com.tripagent.nexus.application.service.AgentExecutionService;
import com.tripagent.nexus.observability.TraceContextProvider;
import io.micrometer.tracing.*;
import jakarta.validation.Validation;
import java.time.Duration;
import java.util.*;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.User;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Stage9A2ASecurityTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final AgentSchemaValidator schema =
      new AgentSchemaValidator(mapper, Validation.buildDefaultValidatorFactory().getValidator());

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "baseline",
        "wrong-agent",
        "forged-task",
        "replayed-context",
        "unauthorized-fields",
        "other-user-data",
        "oversized"
      })
  void validatesResultBeforeRecordingSuccess(String mutation) {
    var registry = mock(AgentRegistry.class);
    var handler = mock(DefaultRequestHandler.class);
    var agent = mock(TripAgent.class);
    var executions = mock(AgentExecutionService.class);
    var tracer = mock(Tracer.class);
    var span = mock(Span.class, RETURNS_SELF);
    var trace = mock(TraceContext.class);
    when(tracer.nextSpan()).thenReturn(span);
    when(span.context()).thenReturn(trace);
    when(trace.traceId()).thenReturn("00000000000000000000000000000901");
    var input =
        new ResearchAgentModels.Input(
            UUID.randomUUID(),
            ContextEnvelope.empty(1, 8000),
            List.of("TestCity"),
            List.of("Museum"));
    var definition = definition(Set.of(), Set.of());
    doReturn(definition).when(agent).definition();
    when(registry.require(AgentName.RESEARCH))
        .thenReturn(new AgentRegistry.Endpoint(agent, handler, mock(TaskStore.class)));
    UUID sdkTask = UUID.randomUUID();
    ObjectNode output = mapper.createObjectNode().put("destinationSummary", "Synthetic");
    output.putArray("candidateAreas");
    output.put("evidenceInsufficient", true);
    if (mutation.equals("unauthorized-fields")) output.put("totalBudget", 999999);
    if (mutation.equals("other-user-data")) output.put("userId", "synthetic-other-user");
    if (mutation.equals("oversized")) output.put("destinationSummary", "x".repeat(1100000));
    var envelope =
        new AgentResultEnvelope<>(
            mutation.equals("forged-task") ? UUID.randomUUID() : sdkTask,
            mutation.equals("wrong-agent") ? AgentName.BUDGET : AgentName.RESEARCH,
            AgentStatus.SUCCEEDED,
            output,
            List.of(),
            List.of(),
            trace.traceId());
    var artifact = mock(Artifact.class);
    when(artifact.parts())
        .thenReturn(List.of(new DataPart(mapper.convertValue(envelope, Object.class))));
    var result =
        Task.builder()
            .id(sdkTask.toString())
            .contextId(
                (mutation.equals("replayed-context") ? UUID.randomUUID() : input.taskId())
                    .toString())
            .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
            .artifacts(List.of(artifact))
            .build();
    when(handler.onMessageSend(any(), any())).thenReturn(result);
    var service =
        new A2ADelegationService(
            registry,
            new ContextEnvelopeReducer(mapper),
            schema,
            mapper,
            new TraceContextProvider(tracer),
            tracer,
            executions,
            mock(AgentCheckpointService.class));
    try {
      var actual = service.delegate(AgentName.RESEARCH, input, "synthetic-user-trip");
      assertThat(actual.status())
          .isEqualTo(mutation.equals("baseline") ? AgentStatus.SUCCEEDED : AgentStatus.FAILED);
      verify(executions).complete(any(), eq(actual), anyLong());
    } finally {
      service.close();
    }
  }

  @Test
  void unregisteredAgentRejected() {
    var registry = new AgentRegistry(List.of());
    try {
      assertThatThrownBy(() -> registry.require(AgentName.RESEARCH))
          .isInstanceOf(IllegalArgumentException.class);
    } finally {
      registry.close();
    }
  }

  @Test
  void agentCardCannotAdvertiseUnapprovedCapability() {
    var agent = mock(TripAgent.class);
    doReturn(definition(Set.of(com.tripagent.nexus.skill.McpToolName.ITINERARY_STORE), Set.of()))
        .when(agent)
        .definition();
    assertThatThrownBy(
            () -> {
              var registry = new AgentRegistry(List.of(agent));
              try {
                registry.require(AgentName.RESEARCH);
              } finally {
                registry.close();
              }
            })
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"other-user", "unauthenticated-owner"})
  void taskOwnershipRequiresAuthentication(String mutation) {
    var authorization = new TenantTaskAuthorizationProvider();
    var owner = caller("synthetic-a", true);
    authorization.recordOwnership(owner, "synthetic-task", null);
    assertThat(authorization.checkRead(owner, "synthetic-task", null)).isTrue();
    var attacker = caller(mutation.equals("other-user") ? "synthetic-b" : "synthetic-a", false);
    assertThat(authorization.checkRead(attacker, "synthetic-task", null)).isFalse();
    assertThat(authorization.checkWrite(attacker, "synthetic-task", null)).isFalse();
  }

  private static ServerCallContext caller(String name, boolean authenticated) {
    return new ServerCallContext(
        new User() {
          public boolean isAuthenticated() {
            return authenticated;
          }

          public String getUsername() {
            return name;
          }
        },
        Map.of(),
        Set.of());
  }

  private static AgentDefinition<ResearchAgentModels.Input, ResearchAgentModels.Output> definition(
      Set<com.tripagent.nexus.skill.McpToolName> advertised,
      Set<com.tripagent.nexus.skill.McpToolName> allowed) {
    return new AgentDefinition<>(
        AgentName.RESEARCH,
        AgentCardFactory.create(AgentName.RESEARCH, "Synthetic", advertised, "1.0.0"),
        ResearchAgentModels.Input.class,
        ResearchAgentModels.Output.class,
        allowed,
        new AgentPolicy(
            Duration.ofSeconds(2),
            new RetryPolicy(1, Duration.ZERO, Duration.ZERO),
            Set.of("A2A_RESULT_INVALID"),
            false,
            "research-v1.0.0"));
  }
}
