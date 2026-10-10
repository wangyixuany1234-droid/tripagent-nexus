package com.tripagent.nexus.agent.budget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AbstractA2aAgent;
import com.tripagent.nexus.agent.AgentCardFactory;
import com.tripagent.nexus.agent.AgentContracts.AgentPolicy;
import com.tripagent.nexus.agent.AgentContracts.AgentWarning;
import com.tripagent.nexus.agent.AgentContracts.RetryPolicy;
import com.tripagent.nexus.agent.AgentDefinition;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.AgentSchemaValidator;
import com.tripagent.nexus.agent.AgentToolSupport;
import com.tripagent.nexus.agent.budget.BudgetAgentModels.CostItem;
import com.tripagent.nexus.agent.budget.BudgetAgentModels.Input;
import com.tripagent.nexus.agent.budget.BudgetAgentModels.Output;
import com.tripagent.nexus.observability.TraceContextProvider;
import com.tripagent.nexus.skill.McpToolGateway;
import com.tripagent.nexus.skill.McpToolName;
import com.tripagent.nexus.skill.McpToolResult;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.agents.enabled", havingValue = "true", matchIfMissing = true)
public class BudgetAgent extends AbstractA2aAgent<Input, Output> {

  public static final String PROMPT_VERSION = "budget-v1.0.0";

  private final McpToolGateway toolGateway;
  private final ObjectMapper objectMapper;

  public BudgetAgent(
      ObjectMapper objectMapper,
      AgentSchemaValidator schemaValidator,
      TraceContextProvider traceContextProvider,
      Tracer tracer,
      McpToolGateway toolGateway) {
    super(createDefinition(), objectMapper, schemaValidator, traceContextProvider, tracer);
    this.toolGateway = toolGateway;
    this.objectMapper = objectMapper;
  }

  @Override
  protected AgentComputation<Output> process(Input input) {
    List<Map<String, Object>> items =
        input.costItems().stream()
            .map(
                item ->
                    Map.<String, Object>of(
                        "category", item.category(),
                        "description", item.description(),
                        "amount", item.amount(),
                        "currency", item.currency()))
            .toList();
    McpToolResult toolResult =
        AgentToolSupport.call(
            toolGateway,
            AgentName.BUDGET,
            McpToolName.BUDGET_VALIDATE,
            Map.of(
                "currency", input.currency(),
                "budgetLimit", input.budgetLimit(),
                "items", items),
            objectMapper);

    List<CostItem> sameCurrency =
        input.costItems().stream()
            .filter(item -> input.currency().equals(item.currency()))
            .toList();
    BigDecimal total =
        sameCurrency.stream()
            .map(CostItem::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(4, RoundingMode.HALF_UP);
    BigDecimal overrun = total.subtract(input.budgetLimit()).max(BigDecimal.ZERO).setScale(4);
    Output output =
        new Output(input.currency(), total, input.budgetLimit(), overrun, input.costItems());
    List<AgentWarning> warnings =
        input.costItems().size() == sameCurrency.size()
            ? AgentToolSupport.warnings(McpToolName.BUDGET_VALIDATE, toolResult)
            : List.of(
                new AgentWarning(
                    "BUDGET_CURRENCY_UNAVAILABLE",
                    "Items in other currencies were excluded until an exchange rate is available."));
    if (toolResult.status() == McpToolResult.Status.SUCCEEDED
        && input.costItems().size() == sameCurrency.size()) {
      return AgentComputation.succeeded(output, List.of(), warnings);
    }
    return AgentComputation.degraded(output, List.of(), warnings);
  }

  private static AgentDefinition<Input, Output> createDefinition() {
    Set<McpToolName> skills = Set.of(McpToolName.EXCHANGE_RATE, McpToolName.BUDGET_VALIDATE);
    return new AgentDefinition<>(
        AgentName.BUDGET,
        AgentCardFactory.create(
            AgentName.BUDGET,
            "Aggregates specialist costs and diagnoses deterministic overruns.",
            skills,
            "1.0.0"),
        Input.class,
        Output.class,
        skills,
        new AgentPolicy(
            Duration.ofSeconds(20),
            new RetryPolicy(2, Duration.ofMillis(200), Duration.ofSeconds(2)),
            Set.of("BUDGET_INPUT_INVALID", "BUDGET_OUTPUT_INVALID", "BUDGET_CURRENCY_UNAVAILABLE"),
            false,
            PROMPT_VERSION));
  }
}
