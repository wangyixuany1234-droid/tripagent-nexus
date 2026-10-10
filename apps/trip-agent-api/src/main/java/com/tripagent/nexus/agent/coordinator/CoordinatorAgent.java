package com.tripagent.nexus.agent.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripagent.nexus.agent.AbstractA2aAgent;
import com.tripagent.nexus.agent.AgentCardFactory;
import com.tripagent.nexus.agent.AgentContracts.AgentEvidence;
import com.tripagent.nexus.agent.AgentContracts.AgentPolicy;
import com.tripagent.nexus.agent.AgentContracts.AgentResultContext;
import com.tripagent.nexus.agent.AgentContracts.AgentResultEnvelope;
import com.tripagent.nexus.agent.AgentContracts.AgentWarning;
import com.tripagent.nexus.agent.AgentContracts.RetryPolicy;
import com.tripagent.nexus.agent.AgentDefinition;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.AgentSchemaValidator;
import com.tripagent.nexus.agent.AgentStatus;
import com.tripagent.nexus.agent.ContextEnvelope;
import com.tripagent.nexus.agent.a2a.A2ADelegationService;
import com.tripagent.nexus.agent.accommodation.AccommodationAgentModels;
import com.tripagent.nexus.agent.budget.BudgetAgentModels;
import com.tripagent.nexus.agent.coordinator.CoordinatorAgentModels.CompletionStatus;
import com.tripagent.nexus.agent.coordinator.CoordinatorAgentModels.DelegatedTask;
import com.tripagent.nexus.agent.coordinator.CoordinatorAgentModels.Input;
import com.tripagent.nexus.agent.coordinator.CoordinatorAgentModels.Output;
import com.tripagent.nexus.agent.coordinator.CoordinatorAgentModels.PlanAlternative;
import com.tripagent.nexus.agent.recommendation.RecommendationAgentModels;
import com.tripagent.nexus.agent.requirement.RequirementAgentModels;
import com.tripagent.nexus.agent.research.ResearchAgentModels;
import com.tripagent.nexus.agent.review.ReviewAgentModels;
import com.tripagent.nexus.agent.transport.TransportAgentModels;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStrategy;
import com.tripagent.nexus.observability.TraceContextProvider;
import com.tripagent.nexus.skill.McpToolName;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.agents.enabled", havingValue = "true", matchIfMissing = true)
public class CoordinatorAgent extends AbstractA2aAgent<Input, Output> {

  public static final String PROMPT_VERSION = "coordinator-v1.0.0";

  private final ObjectProvider<A2ADelegationService> delegationService;
  private final ObjectMapper objectMapper;
  private final ExecutorService candidateExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public CoordinatorAgent(
      ObjectMapper objectMapper,
      AgentSchemaValidator schemaValidator,
      TraceContextProvider traceContextProvider,
      Tracer tracer,
      ObjectProvider<A2ADelegationService> delegationService) {
    super(createDefinition(), objectMapper, schemaValidator, traceContextProvider, tracer);
    this.delegationService = delegationService;
    this.objectMapper = objectMapper;
  }

  @Override
  protected AgentComputation<Output> process(Input input) {
    return coordinate(input);
  }

  public AgentComputation<Output> coordinate(Input input) {
    A2ADelegationService a2a = delegationService.getObject();
    String tenantScope = "planning-task:" + input.taskId();
    ContextEnvelope context = input.context();
    List<AgentResultEnvelope<JsonNode>> results = new ArrayList<>();

    AgentResultEnvelope<JsonNode> requirementResult =
        a2a.delegateStep(
            AgentName.REQUIREMENT,
            new RequirementAgentModels.Input(
                stepId(input, AgentName.REQUIREMENT), context, input.requestText()),
            tenantScope,
            input.taskId(),
            input.executionToken());
    results.add(requirementResult);
    if (!usable(requirementResult)) {
      Output output =
          new Output(
              CompletionStatus.FAILED,
              delegatedTasks(results),
              List.of(),
              objectMapper.createObjectNode());
      return AgentComputation.failed(output, evidence(results), warnings(results));
    }
    RequirementAgentModels.Output requirement =
        objectMapper.convertValue(requirementResult.result(), RequirementAgentModels.Output.class);
    context = withResult(context, requirementResult);

    AgentResultEnvelope<JsonNode> researchResult =
        a2a.delegateStep(
            AgentName.RESEARCH,
            new ResearchAgentModels.Input(
                stepId(input, AgentName.RESEARCH),
                context,
                requirement.destinations(),
                List.of(
                    "destination areas and travel constraints",
                    "opening hours and reservation requirements")),
            tenantScope,
            input.taskId(),
            input.executionToken());
    results.add(researchResult);
    ResearchAgentModels.Output research =
        valueOr(
            researchResult,
            ResearchAgentModels.Output.class,
            new ResearchAgentModels.Output("Evidence unavailable.", List.of(), true));
    context = withResult(context, researchResult);

    ContextEnvelope candidateContext = context;
    OffsetDateTime requestedArrival = arrivalAnchor(requirement, candidateContext);
    CompletableFuture<AgentResultEnvelope<JsonNode>> transportFuture =
        CompletableFuture.supplyAsync(
            () ->
                a2a.delegateStep(
                    AgentName.TRANSPORT,
                    new TransportAgentModels.Input(
                        stepId(input, AgentName.TRANSPORT),
                        candidateContext,
                        requirement.origin(),
                        requirement.destinations()),
                    tenantScope,
                    input.taskId(),
                    input.executionToken()),
            candidateExecutor);
    CompletableFuture<AgentResultEnvelope<JsonNode>> accommodationFuture =
        CompletableFuture.supplyAsync(
            () ->
                a2a.delegateStep(
                    AgentName.ACCOMMODATION,
                    new AccommodationAgentModels.Input(
                        stepId(input, AgentName.ACCOMMODATION),
                        candidateContext,
                        requirement.destinations().getFirst(),
                        requestedArrival,
                        requirement.endDate().atTime(12, 0).atOffset(requestedArrival.getOffset())),
                    tenantScope,
                    input.taskId(),
                    input.executionToken()),
            candidateExecutor);
    CompletableFuture<AgentResultEnvelope<JsonNode>> recommendationFuture =
        CompletableFuture.supplyAsync(
            () ->
                a2a.delegateStep(
                    AgentName.RECOMMENDATION,
                    new RecommendationAgentModels.Input(
                        stepId(input, AgentName.RECOMMENDATION),
                        candidateContext,
                        requirement.destinations().getFirst(),
                        research.candidateAreas()),
                    tenantScope,
                    input.taskId(),
                    input.executionToken()),
            candidateExecutor);

    AgentResultEnvelope<JsonNode> transportResult = transportFuture.join();
    TransportAgentModels.Output transport =
        valueOr(
            transportResult,
            TransportAgentModels.Output.class,
            new TransportAgentModels.Output(List.of()));
    OffsetDateTime arrival = arrivalAnchor(requirement, transport);
    AgentResultEnvelope<JsonNode> accommodationResult = accommodationFuture.join();
    AccommodationAgentModels.Output generatedAccommodation =
        valueOr(
            accommodationResult,
            AccommodationAgentModels.Output.class,
            new AccommodationAgentModels.Output(arrival, List.of()));
    AccommodationAgentModels.Output accommodation =
        new AccommodationAgentModels.Output(arrival, generatedAccommodation.options());
    AgentResultEnvelope<JsonNode> recommendationResult = recommendationFuture.join();
    RecommendationAgentModels.Output recommendation =
        valueOr(
            recommendationResult,
            RecommendationAgentModels.Output.class,
            new RecommendationAgentModels.Output(List.of(), true));

    results.add(transportResult);
    results.add(accommodationResult);
    results.add(recommendationResult);
    context = withResult(context, transportResult);
    context = withResult(context, accommodationResult);
    context = withResult(context, recommendationResult);

    List<BudgetAgentModels.CostItem> costItems =
        collectCosts(transport, accommodation, recommendation);
    AgentResultEnvelope<JsonNode> budgetResult =
        a2a.delegateStep(
            AgentName.BUDGET,
            new BudgetAgentModels.Input(
                stepId(input, AgentName.BUDGET),
                context,
                requirement.budgetLimit(),
                requirement.currency(),
                costItems),
            tenantScope,
            input.taskId(),
            input.executionToken());
    results.add(budgetResult);
    BudgetAgentModels.Output budget =
        valueOr(
            budgetResult,
            BudgetAgentModels.Output.class,
            new BudgetAgentModels.Output(
                requirement.currency(),
                BigDecimal.ZERO.setScale(4),
                requirement.budgetLimit(),
                BigDecimal.ZERO.setScale(4),
                List.of()));
    context = withResult(context, budgetResult);

    ObjectNode plan =
        candidatePlan(requirement, research, transport, accommodation, recommendation, budget);
    AgentResultEnvelope<JsonNode> reviewResult =
        a2a.delegateStep(
            AgentName.REVIEW,
            new ReviewAgentModels.Input(stepId(input, AgentName.REVIEW), context, plan),
            tenantScope,
            input.taskId(),
            input.executionToken());
    results.add(reviewResult);
    ReviewAgentModels.Output review =
        valueOr(
            reviewResult,
            ReviewAgentModels.Output.class,
            new ReviewAgentModels.Output(
                false,
                context.planVersion(),
                List.of(
                    new ReviewAgentModels.ReviewIssue(
                        "REVIEW_UNAVAILABLE",
                        ReviewAgentModels.Severity.ERROR,
                        "ReviewAgent did not produce a usable result."))));
    plan.set("review", objectMapper.valueToTree(review));

    boolean complete =
        review.valid()
            && results.stream().allMatch(result -> result.status() == AgentStatus.SUCCEEDED);
    CompletionStatus completionStatus =
        complete ? CompletionStatus.COMPLETED : CompletionStatus.PARTIALLY_COMPLETED;
    AgentStatus status = complete ? AgentStatus.SUCCEEDED : AgentStatus.DEGRADED;
    List<PlanAlternative> alternatives =
        List.of(
            new PlanAlternative(
                "balanced",
                PlanStrategy.BALANCED,
                requirement.destinations().getFirst() + " balanced plan",
                "Balances historical culture, local dining, travel time, and budget."),
            new PlanAlternative(
                "low-transit",
                PlanStrategy.LOW_TRANSIT,
                requirement.destinations().getFirst() + " low-transit plan",
                "Groups activities by area to reduce daily transit."));
    plan.set("alternatives", objectMapper.valueToTree(alternatives));
    Output output = new Output(completionStatus, delegatedTasks(results), alternatives, plan);
    return status == AgentStatus.SUCCEEDED
        ? AgentComputation.succeeded(output, evidence(results), warnings(results))
        : AgentComputation.degraded(output, evidence(results), warnings(results));
  }

  private ContextEnvelope withResult(
      ContextEnvelope context, AgentResultEnvelope<JsonNode> result) {
    return context.withAgentResult(
        new AgentResultContext(
            result.taskId(), result.agentName(), result.status(), result.result()));
  }

  private static UUID stepId(Input input, AgentName agent) {
    return UUID.nameUUIDFromBytes(
        (input.taskId() + ":" + agent.cardName())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private <T> T valueOr(AgentResultEnvelope<JsonNode> result, Class<T> resultType, T fallback) {
    return usable(result) ? objectMapper.convertValue(result.result(), resultType) : fallback;
  }

  private static boolean usable(AgentResultEnvelope<JsonNode> result) {
    return result.status() == AgentStatus.SUCCEEDED || result.status() == AgentStatus.DEGRADED;
  }

  private static OffsetDateTime arrivalAnchor(
      RequirementAgentModels.Output requirement, TransportAgentModels.Output transport) {
    return transport.options().stream()
        .map(TransportAgentModels.TransportOption::arrivalTime)
        .findFirst()
        .orElse(requirement.startDate().atStartOfDay().atOffset(ZoneOffset.UTC));
  }

  private static OffsetDateTime arrivalAnchor(
      RequirementAgentModels.Output requirement, ContextEnvelope context) {
    return context.parsedBookings().stream()
        .filter(booking -> booking.confirmed() && booking.endTime() != null)
        .map(booking -> booking.endTime().atOffset(ZoneOffset.UTC))
        .findFirst()
        .orElse(requirement.startDate().atTime(14, 0).atOffset(ZoneOffset.ofHours(8)));
  }

  @PreDestroy
  void closeCandidateExecutor() {
    candidateExecutor.shutdownNow();
  }

  private static List<BudgetAgentModels.CostItem> collectCosts(
      TransportAgentModels.Output transport,
      AccommodationAgentModels.Output accommodation,
      RecommendationAgentModels.Output recommendation) {
    List<BudgetAgentModels.CostItem> items = new ArrayList<>();
    transport
        .options()
        .forEach(
            option ->
                items.add(
                    new BudgetAgentModels.CostItem(
                        AgentName.TRANSPORT,
                        "TRANSPORT",
                        option.optionId(),
                        option.cost(),
                        option.currency())));
    accommodation
        .options()
        .forEach(
            option ->
                items.add(
                    new BudgetAgentModels.CostItem(
                        AgentName.ACCOMMODATION,
                        "ACCOMMODATION",
                        option.optionId(),
                        option.cost(),
                        option.currency())));
    recommendation
        .recommendations()
        .forEach(
            item ->
                items.add(
                    new BudgetAgentModels.CostItem(
                        AgentName.RECOMMENDATION,
                        item.type().name(),
                        item.itemId(),
                        item.estimatedCost(),
                        item.currency())));
    return List.copyOf(items);
  }

  private ObjectNode candidatePlan(
      RequirementAgentModels.Output requirement,
      ResearchAgentModels.Output research,
      TransportAgentModels.Output transport,
      AccommodationAgentModels.Output accommodation,
      RecommendationAgentModels.Output recommendation,
      BudgetAgentModels.Output budget) {
    ObjectNode plan = objectMapper.createObjectNode();
    plan.set("requirements", objectMapper.valueToTree(requirement));
    plan.set("research", objectMapper.valueToTree(research));
    plan.set("transport", objectMapper.valueToTree(transport));
    plan.set("accommodation", objectMapper.valueToTree(accommodation));
    plan.set("recommendations", objectMapper.valueToTree(recommendation));
    plan.set("budget", objectMapper.valueToTree(budget));
    return plan;
  }

  private static List<DelegatedTask> delegatedTasks(List<AgentResultEnvelope<JsonNode>> results) {
    return results.stream()
        .map(result -> new DelegatedTask(result.taskId(), result.agentName(), result.status()))
        .toList();
  }

  private static List<AgentEvidence> evidence(List<AgentResultEnvelope<JsonNode>> results) {
    Map<String, AgentEvidence> evidence = new LinkedHashMap<>();
    results.forEach(
        result -> result.evidence().forEach(item -> evidence.putIfAbsent(item.sourceId(), item)));
    return List.copyOf(evidence.values());
  }

  private static List<AgentWarning> warnings(List<AgentResultEnvelope<JsonNode>> results) {
    return results.stream().flatMap(result -> result.warnings().stream()).toList();
  }

  private static AgentDefinition<Input, Output> createDefinition() {
    Set<McpToolName> skills = Set.of(McpToolName.ITINERARY_STORE);
    return new AgentDefinition<>(
        AgentName.COORDINATOR,
        AgentCardFactory.create(
            AgentName.COORDINATOR,
            "Discovers specialists, delegates tasks, and merges validated results.",
            skills,
            "1.0.0"),
        Input.class,
        Output.class,
        skills,
        new AgentPolicy(
            Duration.ofSeconds(120),
            new RetryPolicy(1, Duration.ZERO, Duration.ZERO),
            Set.of(
                "COORDINATOR_INPUT_INVALID",
                "COORDINATOR_AGENT_UNAVAILABLE",
                "COORDINATOR_DEADLINE_EXCEEDED",
                "COORDINATOR_OUTPUT_INVALID"),
            true,
            PROMPT_VERSION));
  }
}
