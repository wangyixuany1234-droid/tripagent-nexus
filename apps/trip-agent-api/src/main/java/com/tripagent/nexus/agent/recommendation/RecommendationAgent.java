package com.tripagent.nexus.agent.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AbstractA2aAgent;
import com.tripagent.nexus.agent.AgentCardFactory;
import com.tripagent.nexus.agent.AgentContracts.AgentPolicy;
import com.tripagent.nexus.agent.AgentContracts.RetryPolicy;
import com.tripagent.nexus.agent.AgentDefinition;
import com.tripagent.nexus.agent.AgentModelInvoker;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.AgentSchemaValidator;
import com.tripagent.nexus.agent.AgentToolSupport;
import com.tripagent.nexus.agent.recommendation.RecommendationAgentModels.Input;
import com.tripagent.nexus.agent.recommendation.RecommendationAgentModels.Output;
import com.tripagent.nexus.observability.TraceContextProvider;
import com.tripagent.nexus.skill.McpToolGateway;
import com.tripagent.nexus.skill.McpToolName;
import com.tripagent.nexus.skill.McpToolResult;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.agents.enabled", havingValue = "true", matchIfMissing = true)
public class RecommendationAgent extends AbstractA2aAgent<Input, Output> {

  public static final String PROMPT_VERSION = "recommendation-v1.0.0";

  private final AgentModelInvoker modelInvoker;
  private final McpToolGateway toolGateway;
  private final ObjectMapper objectMapper;

  public RecommendationAgent(
      ObjectMapper objectMapper,
      AgentSchemaValidator schemaValidator,
      TraceContextProvider traceContextProvider,
      Tracer tracer,
      AgentModelInvoker modelInvoker,
      McpToolGateway toolGateway) {
    super(createDefinition(), objectMapper, schemaValidator, traceContextProvider, tracer);
    this.modelInvoker = modelInvoker;
    this.toolGateway = toolGateway;
    this.objectMapper = objectMapper;
  }

  @Override
  protected AgentComputation<Output> process(Input input) {
    String query =
        input.candidateAreas().isEmpty()
            ? "attractions and restaurants"
            : "attractions and restaurants in " + String.join(", ", input.candidateAreas());
    McpToolResult toolResult =
        AgentToolSupport.call(
            toolGateway,
            AgentName.RECOMMENDATION,
            McpToolName.PLACE_SEARCH,
            Map.of("query", query, "city", input.city(), "maxResults", 12),
            objectMapper);
    Input enriched =
        new Input(
            input.taskId(),
            input
                .context()
                .withToolResult(
                    AgentToolSupport.context(McpToolName.PLACE_SEARCH, toolResult, objectMapper)),
            input.city(),
            input.candidateAreas());
    Output output = modelInvoker.invoke(PROMPT_VERSION, enriched, Output.class);
    if (toolResult.status() == McpToolResult.Status.SUCCEEDED) {
      return AgentComputation.succeeded(
          output,
          enriched.context().retrievedEvidence(),
          AgentToolSupport.warnings(McpToolName.PLACE_SEARCH, toolResult));
    }
    return AgentComputation.degraded(
        output,
        enriched.context().retrievedEvidence(),
        AgentToolSupport.warnings(McpToolName.PLACE_SEARCH, toolResult));
  }

  private static AgentDefinition<Input, Output> createDefinition() {
    Set<McpToolName> skills =
        Set.of(
            McpToolName.PLACE_SEARCH,
            McpToolName.OPENING_HOURS,
            McpToolName.ROUTE_CALCULATE,
            McpToolName.WEATHER_QUERY);
    return new AgentDefinition<>(
        AgentName.RECOMMENDATION,
        AgentCardFactory.create(
            AgentName.RECOMMENDATION,
            "Recommends attractions and dining options backed by evidence.",
            skills,
            "1.0.0"),
        Input.class,
        Output.class,
        skills,
        new AgentPolicy(
            Duration.ofSeconds(35),
            new RetryPolicy(3, Duration.ofMillis(300), Duration.ofSeconds(3)),
            Set.of(
                "RECOMMENDATION_INPUT_INVALID",
                "RECOMMENDATION_OUTPUT_INVALID",
                "RECOMMENDATION_EVIDENCE_INSUFFICIENT",
                "RECOMMENDATION_TIMEOUT"),
            true,
            PROMPT_VERSION));
  }
}
