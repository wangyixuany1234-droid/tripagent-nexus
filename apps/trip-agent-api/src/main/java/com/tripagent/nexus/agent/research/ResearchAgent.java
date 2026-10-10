package com.tripagent.nexus.agent.research;

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
import com.tripagent.nexus.agent.research.ResearchAgentModels.Input;
import com.tripagent.nexus.agent.research.ResearchAgentModels.Output;
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
public class ResearchAgent extends AbstractA2aAgent<Input, Output> {

  public static final String PROMPT_VERSION = "research-v1.0.0";

  private final AgentModelInvoker modelInvoker;
  private final McpToolGateway toolGateway;
  private final ObjectMapper objectMapper;

  public ResearchAgent(
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
    McpToolResult toolResult =
        AgentToolSupport.call(
            toolGateway,
            AgentName.RESEARCH,
            McpToolName.PLACE_SEARCH,
            Map.of(
                "query",
                String.join("; ", input.researchQuestions()),
                "city",
                input.destinations().getFirst(),
                "maxResults",
                10),
            objectMapper);
    Input enriched =
        new Input(
            input.taskId(),
            input
                .context()
                .withToolResult(
                    AgentToolSupport.context(McpToolName.PLACE_SEARCH, toolResult, objectMapper)),
            input.destinations(),
            input.researchQuestions());
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
        Set.of(McpToolName.PLACE_SEARCH, McpToolName.WEATHER_QUERY, McpToolName.OPENING_HOURS);
    return new AgentDefinition<>(
        AgentName.RESEARCH,
        AgentCardFactory.create(
            AgentName.RESEARCH,
            "Researches destinations and packages traceable evidence.",
            skills,
            "1.0.0"),
        Input.class,
        Output.class,
        skills,
        new AgentPolicy(
            Duration.ofSeconds(35),
            new RetryPolicy(3, Duration.ofMillis(300), Duration.ofSeconds(3)),
            Set.of(
                "RESEARCH_INPUT_INVALID",
                "RESEARCH_OUTPUT_INVALID",
                "RESEARCH_EVIDENCE_INSUFFICIENT",
                "RESEARCH_TOOL_UNAVAILABLE"),
            true,
            PROMPT_VERSION));
  }
}
