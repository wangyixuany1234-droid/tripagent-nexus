package com.tripagent.nexus.agent.transport;

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
import com.tripagent.nexus.agent.transport.TransportAgentModels.Input;
import com.tripagent.nexus.agent.transport.TransportAgentModels.Output;
import com.tripagent.nexus.observability.TraceContextProvider;
import com.tripagent.nexus.skill.McpToolGateway;
import com.tripagent.nexus.skill.McpToolName;
import com.tripagent.nexus.skill.McpToolResult;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.agents.enabled", havingValue = "true", matchIfMissing = true)
public class TransportAgent extends AbstractA2aAgent<Input, Output> {

  public static final String PROMPT_VERSION = "transport-v1.0.0";

  private final AgentModelInvoker modelInvoker;
  private final McpToolGateway toolGateway;
  private final ObjectMapper objectMapper;

  public TransportAgent(
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
    McpToolResult routeResult = route(input);
    Input enriched =
        new Input(
            input.taskId(),
            input
                .context()
                .withToolResult(
                    AgentToolSupport.context(
                        McpToolName.ROUTE_CALCULATE, routeResult, objectMapper)),
            input.origin(),
            input.destinations());
    Output output = modelInvoker.invoke(PROMPT_VERSION, enriched, Output.class);
    if (routeResult.status() == McpToolResult.Status.SUCCEEDED) {
      return AgentComputation.succeeded(
          output,
          enriched.context().retrievedEvidence(),
          AgentToolSupport.warnings(McpToolName.ROUTE_CALCULATE, routeResult));
    }
    return AgentComputation.degraded(
        output,
        enriched.context().retrievedEvidence(),
        AgentToolSupport.warnings(McpToolName.ROUTE_CALCULATE, routeResult));
  }

  private McpToolResult route(Input input) {
    Instant departure =
        input.context().parsedBookings().stream()
            .filter(booking -> booking.confirmed() && booking.endTime() != null)
            .map(booking -> booking.endTime())
            .findFirst()
            .orElse(null);
    if (departure == null) {
      var data = objectMapper.createObjectNode();
      data.put("code", "ROUTE_DEPARTURE_TIME_MISSING");
      return new McpToolResult(
          McpToolResult.Status.UNAVAILABLE,
          McpToolResult.ProviderMode.INTERNAL,
          data,
          List.of("No confirmed arrival anchor is available."));
    }
    return AgentToolSupport.call(
        toolGateway,
        AgentName.TRANSPORT,
        McpToolName.ROUTE_CALCULATE,
        Map.of(
            "origin",
            input.origin(),
            "destination",
            input.destinations().getFirst(),
            "departureTime",
            departure.toString(),
            "modes",
            List.of("TRAIN", "METRO", "TAXI")),
        objectMapper);
  }

  private static AgentDefinition<Input, Output> createDefinition() {
    Set<McpToolName> skills =
        Set.of(
            McpToolName.PLACE_SEARCH,
            McpToolName.WEATHER_QUERY,
            McpToolName.ROUTE_CALCULATE,
            McpToolName.EXCHANGE_RATE);
    return new AgentDefinition<>(
        AgentName.TRANSPORT,
        AgentCardFactory.create(
            AgentName.TRANSPORT,
            "Produces intercity and local transport candidates with arrival anchors.",
            skills,
            "1.0.0"),
        Input.class,
        Output.class,
        skills,
        new AgentPolicy(
            Duration.ofSeconds(35),
            new RetryPolicy(3, Duration.ofMillis(300), Duration.ofSeconds(3)),
            Set.of(
                "TRANSPORT_INPUT_INVALID",
                "TRANSPORT_OUTPUT_INVALID",
                "TRANSPORT_ROUTE_UNAVAILABLE",
                "TRANSPORT_TIMEOUT"),
            true,
            PROMPT_VERSION));
  }
}
