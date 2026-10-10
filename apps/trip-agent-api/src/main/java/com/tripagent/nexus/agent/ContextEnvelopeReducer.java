package com.tripagent.nexus.agent;

import static com.tripagent.nexus.agent.AgentName.ACCOMMODATION;
import static com.tripagent.nexus.agent.AgentName.BUDGET;
import static com.tripagent.nexus.agent.AgentName.RECOMMENDATION;
import static com.tripagent.nexus.agent.AgentName.REQUIREMENT;
import static com.tripagent.nexus.agent.AgentName.RESEARCH;
import static com.tripagent.nexus.agent.AgentName.TRANSPORT;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripagent.nexus.agent.AgentContracts.AgentEvidence;
import com.tripagent.nexus.agent.AgentContracts.AgentResultContext;
import com.tripagent.nexus.agent.AgentContracts.BookingContext;
import com.tripagent.nexus.agent.AgentContracts.ConstraintKind;
import com.tripagent.nexus.agent.AgentContracts.PrivacyClassification;
import com.tripagent.nexus.agent.AgentContracts.ToolResultContext;
import com.tripagent.nexus.agent.AgentContracts.UserConstraint;
import com.tripagent.nexus.skill.McpToolName;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class ContextEnvelopeReducer {

  private static final int EVIDENCE_SUMMARY_CHARS = 240;
  private static final Set<String> RESEARCH_TOOLS =
      Set.of("place_search", "weather_query", "opening_hours");
  private static final Set<String> TRANSPORT_TOOLS =
      Set.of("place_search", "weather_query", "route_calculate", "exchange_rate");
  private static final Set<String> ACCOMMODATION_TOOLS =
      Set.of("place_search", "route_calculate", "opening_hours", "exchange_rate");
  private static final Set<String> RECOMMENDATION_TOOLS =
      Set.of("place_search", "opening_hours", "route_calculate", "weather_query");
  private static final Set<String> BUDGET_TOOLS = Set.of("exchange_rate", "budget_validate");
  private static final Set<String> REVIEW_TOOLS =
      Set.of("route_calculate", "opening_hours", "budget_validate");

  private final ObjectMapper objectMapper;

  public ContextEnvelopeReducer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public ContextEnvelope select(AgentName agentName, ContextEnvelope source) {
    Responsibility responsibility = Responsibility.forAgent(agentName);
    ContextEnvelope selected =
        new ContextEnvelope(
            distinct(source.systemRules(), Function.identity()),
            distinct(
                allowedItems(source, "/userConstraints", source.userConstraints(), agentName),
                UserConstraint::constraintId),
            responsibility.bookings()
                ? distinct(
                    allowedItems(source, "/parsedBookings", source.parsedBookings(), agentName),
                    booking -> booking.bookingId().toString())
                : List.of(),
            responsibility.evidence()
                ? distinct(
                    allowedItems(
                        source, "/retrievedEvidence", source.retrievedEvidence(), agentName),
                    AgentEvidence::sourceId)
                : List.of(),
            allowedItems(source, "/toolResults", source.toolResults(), agentName).stream()
                .filter(result -> responsibility.tools().contains(result.toolName()))
                .collect(
                    java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(
                            ToolResultContext::toolName,
                            Function.identity(),
                            (first, ignored) -> first,
                            LinkedHashMap::new),
                        values -> List.copyOf(values.values()))),
            allowedItems(source, "/agentResults", source.agentResults(), agentName).stream()
                .filter(result -> responsibility.agentResults().contains(result.agentName()))
                .toList(),
            source.planVersion(),
            source.tokenBudget(),
            source.privacyLabels().stream()
                .filter(label -> label.allowedAgents().contains(agentName))
                .toList());
    return fitBudget(selected);
  }

  private ContextEnvelope fitBudget(ContextEnvelope source) {
    if (estimatedTokens(source) <= source.tokenBudget()) {
      return source;
    }

    List<AgentEvidence> evidence =
        source.retrievedEvidence().stream()
            .map(item -> item.summarized(EVIDENCE_SUMMARY_CHARS))
            .sorted(Comparator.comparing(AgentEvidence::relevanceScore).reversed())
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    List<ToolResultContext> tools = new ArrayList<>(source.toolResults());
    List<AgentResultContext> agentResults = new ArrayList<>(source.agentResults());
    List<UserConstraint> constraints = new ArrayList<>(source.userConstraints());
    List<BookingContext> bookings = new ArrayList<>(source.parsedBookings());

    ContextEnvelope reduced = copy(source, constraints, bookings, evidence, tools, agentResults);
    while (estimatedTokens(reduced) > source.tokenBudget() && !evidence.isEmpty()) {
      evidence.removeLast();
      reduced = copy(source, constraints, bookings, evidence, tools, agentResults);
    }
    while (estimatedTokens(reduced) > source.tokenBudget() && !tools.isEmpty()) {
      ToolResultContext result = tools.removeLast();
      tools.add(summarize(result));
      reduced = copy(source, constraints, bookings, evidence, tools, agentResults);
      if (tools.getLast().equals(result)) {
        tools.removeLast();
      }
    }
    while (estimatedTokens(reduced) > source.tokenBudget() && !agentResults.isEmpty()) {
      agentResults.removeLast();
      reduced = copy(source, constraints, bookings, evidence, tools, agentResults);
    }
    while (estimatedTokens(reduced) > source.tokenBudget()
        && constraints.removeIf(
            item -> item.kind() == ConstraintKind.SOFT && !item.protectedFromSummarization())) {
      reduced = copy(source, constraints, bookings, evidence, tools, agentResults);
    }
    while (estimatedTokens(reduced) > source.tokenBudget()
        && bookings.removeIf(booking -> !booking.confirmed())) {
      reduced = copy(source, constraints, bookings, evidence, tools, agentResults);
    }
    return reduced;
  }

  private ToolResultContext summarize(ToolResultContext result) {
    ObjectNode summary = objectMapper.createObjectNode();
    summary.put("summary", "Tool output omitted to fit the delegated token budget.");
    ToolResultContext summarized =
        new ToolResultContext(result.toolName(), result.status(), summary);
    return summarized.result().equals(result.result()) ? result : summarized;
  }

  private ContextEnvelope copy(
      ContextEnvelope source,
      List<UserConstraint> constraints,
      List<BookingContext> bookings,
      List<AgentEvidence> evidence,
      List<ToolResultContext> tools,
      List<AgentResultContext> agentResults) {
    return new ContextEnvelope(
        source.systemRules(),
        List.copyOf(constraints),
        List.copyOf(bookings),
        List.copyOf(evidence),
        List.copyOf(tools),
        List.copyOf(agentResults),
        source.planVersion(),
        source.tokenBudget(),
        source.privacyLabels());
  }

  private int estimatedTokens(ContextEnvelope context) {
    try {
      return Math.max(1, objectMapper.writeValueAsBytes(context).length / 4);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("ContextEnvelope could not be sized.", exception);
    }
  }

  private static <T> List<T> allowedItems(
      ContextEnvelope source, String sectionPointer, List<T> items, AgentName agentName) {
    List<T> selected = new ArrayList<>();
    for (int index = 0; index < items.size(); index++) {
      if (allowed(source, sectionPointer + "/" + index, agentName)) {
        selected.add(items.get(index));
      }
    }
    return List.copyOf(selected);
  }

  private static boolean allowed(ContextEnvelope source, String itemPointer, AgentName agentName) {
    return source.privacyLabels().stream()
        .filter(
            label ->
                label.jsonPointer().equals(itemPointer)
                    || label.jsonPointer().startsWith(itemPointer + "/")
                    || itemPointer.startsWith(label.jsonPointer() + "/"))
        .filter(
            label ->
                label.classification() == PrivacyClassification.PERSONAL
                    || label.classification() == PrivacyClassification.SENSITIVE)
        .allMatch(label -> label.allowedAgents().contains(agentName));
  }

  private static <T> List<T> distinct(List<T> source, Function<T, String> key) {
    Map<String, T> values = new LinkedHashMap<>();
    source.forEach(item -> values.putIfAbsent(key.apply(item), item));
    return List.copyOf(values.values());
  }

  private record Responsibility(
      boolean bookings, boolean evidence, Set<String> tools, Set<AgentName> agentResults) {

    private static Responsibility forAgent(AgentName agentName) {
      return switch (agentName) {
        case REQUIREMENT -> new Responsibility(true, false, Set.of("booking_parse"), Set.of());
        case RESEARCH -> new Responsibility(false, true, RESEARCH_TOOLS, Set.of(REQUIREMENT));
        case TRANSPORT ->
            new Responsibility(true, true, TRANSPORT_TOOLS, Set.of(REQUIREMENT, RESEARCH));
        case ACCOMMODATION ->
            new Responsibility(
                true, true, ACCOMMODATION_TOOLS, Set.of(REQUIREMENT, RESEARCH, TRANSPORT));
        case RECOMMENDATION ->
            new Responsibility(
                false, true, RECOMMENDATION_TOOLS, Set.of(REQUIREMENT, RESEARCH, ACCOMMODATION));
        case BUDGET ->
            new Responsibility(
                true, false, BUDGET_TOOLS, Set.of(TRANSPORT, ACCOMMODATION, RECOMMENDATION));
        case REVIEW ->
            new Responsibility(
                true, true, REVIEW_TOOLS, Set.of(TRANSPORT, ACCOMMODATION, RECOMMENDATION, BUDGET));
        case COORDINATOR ->
            new Responsibility(
                true, true, new LinkedHashSet<>(McpToolName.names()), Set.of(AgentName.values()));
      };
    }
  }
}
