package com.tripagent.nexus.agent.accommodation;

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
import com.tripagent.nexus.agent.accommodation.AccommodationAgentModels.Input;
import com.tripagent.nexus.agent.accommodation.AccommodationAgentModels.Output;
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
public class AccommodationAgent extends AbstractA2aAgent<Input, Output> {

  public static final String PROMPT_VERSION = "accommodation-v1.0.0";

  private final AgentModelInvoker modelInvoker;
  private final McpToolGateway toolGateway;
  private final ObjectMapper objectMapper;

  public AccommodationAgent(
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
            AgentName.ACCOMMODATION,
            McpToolName.PLACE_SEARCH,
            Map.of(
                "query",
                "hotel area matching confirmed arrival",
                "city",
                input.city(),
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
            input.city(),
            input.checkIn(),
            input.checkOut());
    Output generated = modelInvoker.invoke(PROMPT_VERSION, enriched, Output.class);
    Output output = new Output(input.checkIn(), generated.options());
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
            McpToolName.ROUTE_CALCULATE,
            McpToolName.OPENING_HOURS,
            McpToolName.EXCHANGE_RATE);
    return new AgentDefinition<>(
        AgentName.ACCOMMODATION,
        AgentCardFactory.create(
            AgentName.ACCOMMODATION,
            "Selects stay areas and evaluates hotel constraints after arrival.",
            skills,
            "1.0.0"),
        Input.class,
        Output.class,
        skills,
        new AgentPolicy(
            Duration.ofSeconds(35),
            new RetryPolicy(3, Duration.ofMillis(300), Duration.ofSeconds(3)),
            Set.of(
                "ACCOMMODATION_INPUT_INVALID",
                "ACCOMMODATION_OUTPUT_INVALID",
                "ACCOMMODATION_INVENTORY_UNAVAILABLE",
                "ACCOMMODATION_TIMEOUT"),
            true,
            PROMPT_VERSION));
  }
}
