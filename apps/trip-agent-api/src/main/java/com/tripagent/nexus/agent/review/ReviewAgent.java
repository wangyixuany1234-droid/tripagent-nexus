package com.tripagent.nexus.agent.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AbstractA2aAgent;
import com.tripagent.nexus.agent.AgentCardFactory;
import com.tripagent.nexus.agent.AgentContracts.AgentPolicy;
import com.tripagent.nexus.agent.AgentContracts.RetryPolicy;
import com.tripagent.nexus.agent.AgentDefinition;
import com.tripagent.nexus.agent.AgentModelInvoker;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.AgentSchemaValidator;
import com.tripagent.nexus.agent.review.ReviewAgentModels.Input;
import com.tripagent.nexus.agent.review.ReviewAgentModels.Output;
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
public class ReviewAgent extends AbstractA2aAgent<Input, Output> {

  public static final String PROMPT_VERSION = "review-v1.0.0";

  private final AgentModelInvoker modelInvoker;

  public ReviewAgent(
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
    Output generated = modelInvoker.invoke(PROMPT_VERSION, input, Output.class);
    Output output =
        new Output(generated.valid(), input.context().planVersion(), generated.issues());
    return AgentComputation.succeeded(output, input.context().retrievedEvidence(), List.of());
  }

  private static AgentDefinition<Input, Output> createDefinition() {
    Set<McpToolName> skills =
        Set.of(McpToolName.ROUTE_CALCULATE, McpToolName.OPENING_HOURS, McpToolName.BUDGET_VALIDATE);
    return new AgentDefinition<>(
        AgentName.REVIEW,
        AgentCardFactory.create(
            AgentName.REVIEW,
            "Reviews time, route, opening-hours, budget, and evidence consistency.",
            skills,
            "1.0.0"),
        Input.class,
        Output.class,
        skills,
        new AgentPolicy(
            Duration.ofSeconds(30),
            new RetryPolicy(2, Duration.ofMillis(200), Duration.ofSeconds(2)),
            Set.of(
                "REVIEW_INPUT_INVALID",
                "REVIEW_OUTPUT_INVALID",
                "REVIEW_EVIDENCE_INSUFFICIENT",
                "REVIEW_TIMEOUT"),
            false,
            PROMPT_VERSION));
  }
}
