package com.tripagent.nexus.agent.requirement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AbstractA2aAgent;
import com.tripagent.nexus.agent.AgentCardFactory;
import com.tripagent.nexus.agent.AgentContracts.AgentPolicy;
import com.tripagent.nexus.agent.AgentContracts.RetryPolicy;
import com.tripagent.nexus.agent.AgentDefinition;
import com.tripagent.nexus.agent.AgentModelInvoker;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.AgentSchemaValidator;
import com.tripagent.nexus.agent.requirement.RequirementAgentModels.Input;
import com.tripagent.nexus.agent.requirement.RequirementAgentModels.Output;
import com.tripagent.nexus.observability.TraceContextProvider;
import com.tripagent.nexus.skill.McpToolName;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.agents.enabled", havingValue = "true", matchIfMissing = true)
public class RequirementAgent extends AbstractA2aAgent<Input, Output> {

  public static final String PROMPT_VERSION = "requirement-v1.0.0";

  private final AgentModelInvoker modelInvoker;

  public RequirementAgent(
      ObjectMapper objectMapper,
      AgentSchemaValidator schemaValidator,
      TraceContextProvider traceContextProvider,
      Tracer tracer,
      AgentModelInvoker modelInvoker) {
    super(createDefinition(), objectMapper, schemaValidator, traceContextProvider, tracer);
    this.modelInvoker = modelInvoker;
  }

  @Override
  protected AgentComputation<Output> process(Input input) {
    return AgentComputation.succeeded(
        modelInvoker.invoke(PROMPT_VERSION, input, Output.class),
        input.context().retrievedEvidence(),
        List.of());
  }

  private static AgentDefinition<Input, Output> createDefinition() {
    Set<McpToolName> skills = Set.of(McpToolName.BOOKING_PARSE);
    return new AgentDefinition<>(
        AgentName.REQUIREMENT,
        AgentCardFactory.create(
            AgentName.REQUIREMENT,
            "Normalizes travel requirements without weakening hard constraints.",
            skills,
            "1.0.0"),
        Input.class,
        Output.class,
        skills,
        new AgentPolicy(
            Duration.ofSeconds(20),
            new RetryPolicy(2, Duration.ofMillis(200), Duration.ofSeconds(2)),
            Set.of(
                "REQUIREMENT_INPUT_INVALID",
                "REQUIREMENT_OUTPUT_INVALID",
                "REQUIREMENT_MODEL_UNAVAILABLE"),
            false,
            PROMPT_VERSION));
  }
}
