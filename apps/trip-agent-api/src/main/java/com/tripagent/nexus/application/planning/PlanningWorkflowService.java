package com.tripagent.nexus.application.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AgentContracts.AgentEvidence;
import com.tripagent.nexus.agent.AgentContracts.AgentResultEnvelope;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.AgentStatus;
import com.tripagent.nexus.agent.ContextEnvelope;
import com.tripagent.nexus.agent.a2a.A2ADelegationService;
import com.tripagent.nexus.agent.coordinator.CoordinatorAgentModels;
import com.tripagent.nexus.agent.coordinator.CoordinatorAgentModels.CompletionStatus;
import com.tripagent.nexus.agent.coordinator.CoordinatorAgentModels.PlanAlternative;
import com.tripagent.nexus.application.planning.PlanRepairService.RepairResult;
import com.tripagent.nexus.application.planning.PlanVersionStore.PlanningData;
import com.tripagent.nexus.application.planning.PlanningKnowledgeService.KnowledgeResult;
import com.tripagent.nexus.application.planning.PlanningModels.AssembledCandidate;
import com.tripagent.nexus.application.planning.PlanningModels.PlanCandidate;
import com.tripagent.nexus.application.planning.PlanningModels.RuleContext;
import com.tripagent.nexus.application.service.TaskApplicationService;
import com.tripagent.nexus.domain.model.Conflict;
import com.tripagent.nexus.domain.model.DayPlan;
import com.tripagent.nexus.domain.model.DomainEnums.ConflictStatus;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStatus;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStrategy;
import com.tripagent.nexus.domain.model.DomainEnums.Severity;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.domain.model.ItineraryItem;
import com.tripagent.nexus.domain.model.ReplanInputSnapshot;
import com.tripagent.nexus.domain.model.TravelPlan;
import com.tripagent.nexus.infrastructure.config.PlanningProperties;
import com.tripagent.nexus.integration.messaging.AsyncOperationException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.planning.enabled", havingValue = "true", matchIfMissing = true)
public class PlanningWorkflowService {

  private static final Logger log = LoggerFactory.getLogger(PlanningWorkflowService.class);

  private final PlanVersionStore versionStore;
  private final PlanningKnowledgeService knowledgeService;
  private final PlanningContextFactory contextFactory;
  private final A2ADelegationService delegationService;
  private final PlanCandidateAssembler candidateAssembler;
  private final PlanRuleEngine ruleEngine;
  private final PlanRepairService repairService;
  private final PlanLifecycleEventService lifecycleEventService;
  private final TaskApplicationService taskService;
  private final PlanningProperties properties;
  private final ObjectMapper objectMapper;
  private final Tracer tracer;
  private final PlanningCheckpointService checkpoints;

  public PlanningWorkflowService(
      PlanVersionStore versionStore,
      PlanningKnowledgeService knowledgeService,
      PlanningContextFactory contextFactory,
      A2ADelegationService delegationService,
      PlanCandidateAssembler candidateAssembler,
      PlanRuleEngine ruleEngine,
      PlanRepairService repairService,
      PlanLifecycleEventService lifecycleEventService,
      TaskApplicationService taskService,
      PlanningProperties properties,
      ObjectMapper objectMapper,
      Tracer tracer,
      PlanningCheckpointService checkpoints) {
    this.versionStore = versionStore;
    this.knowledgeService = knowledgeService;
    this.contextFactory = contextFactory;
    this.delegationService = delegationService;
    this.candidateAssembler = candidateAssembler;
    this.ruleEngine = ruleEngine;
    this.repairService = repairService;
    this.lifecycleEventService = lifecycleEventService;
    this.taskService = taskService;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.tracer = tracer;
    this.checkpoints = checkpoints;
  }

  public void execute(UUID taskId, UUID planId, TaskType taskType) {
    var acquired = checkpoints.tryLock(taskId);
    if (acquired.isEmpty()) return;
    try (var lock = acquired.orElseThrow()) {
      executeLocked(taskId, planId, taskType, lock);
    }
  }

  public void executeLocked(
      UUID taskId, UUID planId, TaskType taskType, PlanningCheckpointService.ExecutionLock lock) {
    Span span =
        tracer
            .nextSpan()
            .name("planning.workflow")
            .tag("task.id", taskId.toString())
            .tag("plan.id", planId.toString())
            .tag("task.type", taskType.name())
            .start();
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      run(taskId, planId, taskType, lock);
    } catch (TaskCancelledSignal ignored) {
      log.info("Planning workflow cancelled planId={} taskId={}", planId, taskId);
    } catch (RuntimeException exception) {
      span.error(exception);
      if (!lock.isValid()) {
        throw new AsyncOperationException(
            "PLANNING_EXECUTION_LOST",
            true,
            "Planning was interrupted and can be resumed.",
            exception);
      }
      fail(taskId, planId, exception);
      throw new AsyncOperationException(
          exception instanceof PlanningWorkflowException workflowException
              ? workflowException.code()
              : "PLAN_GENERATION_FAILED",
          false,
          "Travel planning could not be completed. Review the failure code and retry if needed.",
          exception);
    } finally {
      span.end();
      if (taskService.get(taskId).status().isTerminal()) checkpoints.remove(taskId);
    }
  }

  private void run(
      UUID taskId, UUID planId, TaskType taskType, PlanningCheckpointService.ExecutionLock lock) {
    var task = taskService.get(taskId);
    if (task.status().isTerminal()) {
      return;
    }
    lock.requireValid();
    ensureActive(taskId);
    PlanningData data = versionStore.load(planId);
    ReplanInputSnapshot replanInput = data.plan().replanInput();
    String tenant = properties.getTenantId() + ":" + data.userId();
    String inputHash =
        checkpoints.fingerprint(
            List.of(
                data.requirement(),
                data.userId(),
                data.zoneId().getId(),
                data.plan().planId(),
                data.plan().version(),
                replanInput,
                data.bookings().stream()
                    .map(booking -> List.of(booking.getBookingId(), booking.getUpdatedAt()))
                    .toList(),
                properties.getProviderMode(),
                properties.getCandidateTarget(),
                properties.getMaxAutoReplans(),
                properties.getTokenBudget(),
                properties.getDefaultCommuteMinutes()));
    PlanningCheckpointService.Snapshot snapshot;
    if (task.status() == TaskStatus.RUNNING && "AGENTS_RUNNING".equals(task.stage())) {
      if (!checkpoints.resumable(taskId)) throw PlanningCheckpointService.invalid();
      snapshot = checkpoints.resume(taskId, inputHash, tenant);
    } else {
      if (task.progress() > 10) throw PlanningCheckpointService.invalid();
      taskService.transition(
          taskId, TaskStatus.RUNNING, "REQUIREMENTS_LOADED", 10, "Planning inputs loaded");
      KnowledgeResult initialKnowledge = knowledgeService.search(data.userId(), data.requirement());
      ensureActive(taskId);
      taskService.transition(
          taskId,
          TaskStatus.RUNNING,
          "RESEARCH_READY",
          25,
          initialKnowledge.evidenceInsufficient()
              ? "Knowledge retrieval completed with gaps"
              : "Public knowledge and user memory retrieved");
      ContextEnvelope context =
          contextFactory.create(
              data.requirement(),
              data.bookings(),
              initialKnowledge,
              data.plan().version(),
              replanInput);
      snapshot =
          checkpoints.create(
              taskId,
              inputHash,
              tenant,
              new CoordinatorAgentModels.Input(taskId, context, requestText(data)),
              initialKnowledge);
      taskService.transition(
          taskId, TaskStatus.RUNNING, "AGENTS_RUNNING", 40, "Planning Agents are running");
    }
    KnowledgeResult knowledge = snapshot.knowledge();
    AgentResultEnvelope<JsonNode> coordinatorResult =
        delegationService.delegate(AgentName.COORDINATOR, snapshot.input(), tenant);
    lock.requireValid();
    ensureActive(taskId);
    if (coordinatorResult.status() == AgentStatus.FAILED
        || coordinatorResult.status() == AgentStatus.TIMEOUT) {
      throw new PlanningWorkflowException("COORDINATOR_FAILED");
    }
    CoordinatorAgentModels.Output coordinator =
        objectMapper.convertValue(coordinatorResult.result(), CoordinatorAgentModels.Output.class);
    if (taskType == TaskType.PLAN_GENERATION && coordinator.alternatives().size() < 2) {
      throw new PlanningWorkflowException("CANDIDATE_TARGET_NOT_MET");
    }

    List<AgentEvidence> evidence =
        mergeEvidence(knowledge.evidence(), coordinatorResult.evidence());
    taskService.transition(
        taskId, TaskStatus.RUNNING, "CANDIDATES_ASSEMBLED", 65, "Candidate itineraries assembled");

    List<UUID> resultPlanIds =
        taskType == TaskType.PLAN_GENERATION
            ? generateInitialCandidates(taskId, data, coordinator, evidence)
            : generateReplan(taskId, data, coordinator, evidence);
    taskService.setResultResources(taskId, resultPlanIds);
    List<String> degraded = new ArrayList<>();
    if (knowledge.evidenceInsufficient()
        || knowledge.warnings().stream()
            .anyMatch(warning -> !"PLANNING_RETRIEVAL_MOCK".equals(warning))) {
      degraded.add("RETRIEVAL");
    }
    if (coordinator.completionStatus() == CompletionStatus.PARTIALLY_COMPLETED
        || coordinatorResult.status() == AgentStatus.DEGRADED) {
      degraded.add("AGENT_ORCHESTRATION");
    }
    lifecycleEventService.enqueueCompleted(
        taskId, data.plan().tripId(), taskService.get(taskId).traceId(), resultPlanIds, degraded);
    if (degraded.isEmpty()) {
      taskService.transition(
          taskId, TaskStatus.SUCCEEDED, "COMPLETED", 100, "Travel planning completed");
    } else {
      taskService.completePartially(
          taskId,
          "Travel planning completed with limited external data; review the listed gaps.",
          degraded);
    }
  }

  private List<UUID> generateInitialCandidates(
      UUID taskId,
      PlanningData data,
      CoordinatorAgentModels.Output coordinator,
      List<AgentEvidence> evidence) {
    List<UUID> resultIds = new ArrayList<>();
    List<PlanAlternative> alternatives =
        coordinator.alternatives().stream().limit(properties.getCandidateTarget()).toList();
    List<ValidatedCandidate> validated = new ArrayList<>();
    for (int index = 0; index < alternatives.size(); index++) {
      PlanAlternative alternative = alternatives.get(index);
      UUID familyId = index == 0 ? data.plan().planFamilyId() : UUID.randomUUID();
      AssembledCandidate assembled =
          candidateAssembler.assemble(
              familyId,
              alternative.candidateKey(),
              alternative.strategy(),
              data.requirement(),
              data.zoneId(),
              coordinator.plan(),
              evidence,
              data.bookings(),
              null);
      List<Conflict> conflicts =
          ruleEngine.validate(assembled.candidate(), assembled.ruleContext());
      validated.add(new ValidatedCandidate(familyId, assembled, conflicts));
      taskService.transition(
          taskId,
          TaskStatus.RUNNING,
          "RULE_VALIDATION",
          70 + index * 5,
          "Candidate " + (index + 1) + " validated by Java rules");
    }
    for (int index = 0; index < validated.size(); index++) {
      ValidatedCandidate candidate = validated.get(index);
      TravelPlan initial =
          index == 0
              ? versionStore.complete(
                  data.plan().planId(),
                  candidate.assembled().candidate(),
                  candidate.conflicts(),
                  status(candidate.conflicts()),
                  "Initial candidate generated.")
              : versionStore.createCompletedAlternative(
                  data.plan().tripId(),
                  candidate.familyId(),
                  candidate.assembled().candidate(),
                  candidate.conflicts(),
                  status(candidate.conflicts()),
                  "INITIAL_GENERATION");
      TravelPlan finalPlan =
          index == 0
              ? autoReplan(taskId, initial, candidate.assembled(), candidate.conflicts())
              : initial;
      resultIds.add(finalPlan.planId());
    }
    return List.copyOf(resultIds);
  }

  private List<UUID> generateReplan(
      UUID taskId,
      PlanningData data,
      CoordinatorAgentModels.Output coordinator,
      List<AgentEvidence> evidence) {
    PlanStrategy strategy = data.plan().strategy();
    String key = data.plan().alternativeKey();
    AssembledCandidate assembled =
        candidateAssembler.assemble(
            data.plan().planFamilyId(),
            key,
            strategy,
            data.requirement(),
            data.zoneId(),
            coordinator.plan(),
            evidence,
            data.bookings(),
            data.plan().replanInput().adjustedTotalBudget());
    PlanCandidate candidate =
        preserveLockedItems(
            assembled.candidate(),
            data.previousPlan() == null ? data.plan() : data.previousPlan(),
            data.plan().replanInput().lockedItemIds());
    RepairResult requested =
        repairService.repair(
            candidate,
            adjustedContext(assembled.ruleContext(), data.plan().replanInput()),
            List.of(),
            Set.copyOf(data.plan().replanInput().lockedItemIds()),
            Set.copyOf(data.plan().replanInput().removedItemIds()),
            data.plan().replanInput().adjustedTotalBudget(),
            data.plan().triggerReason());
    RuleContext context = adjustedContext(assembled.ruleContext(), data.plan().replanInput());
    List<Conflict> conflicts = ruleEngine.validate(requested.candidate(), context);
    taskService.transition(
        taskId, TaskStatus.RUNNING, "RULE_VALIDATION", 75, "Replanned version validated");
    TravelPlan completed =
        versionStore.complete(
            data.plan().planId(),
            requested.candidate(),
            conflicts,
            status(conflicts),
            requested.changeSummary());
    return List.of(
        autoReplan(
                taskId,
                completed,
                new AssembledCandidate(requested.candidate(), context),
                conflicts)
            .planId());
  }

  private TravelPlan autoReplan(
      UUID taskId, TravelPlan current, AssembledCandidate assembled, List<Conflict> conflicts) {
    boolean fixable =
        conflicts.stream()
            .anyMatch(
                conflict ->
                    conflict.status() == ConflictStatus.OPEN
                        && conflict.autoFixable()
                        && conflict.severity() == Severity.ERROR);
    if (!fixable || properties.getMaxAutoReplans() == 0) {
      return current;
    }
    taskService.transition(
        taskId,
        TaskStatus.RUNNING,
        "LOCAL_REPLAN",
        90,
        "One bounded local replan is resolving deterministic conflicts");
    RepairResult repaired =
        repairService.repair(
            assembled.candidate(),
            assembled.ruleContext(),
            conflicts,
            Set.of(),
            Set.of(),
            null,
            "AUTO_RULE_CONFLICT");
    List<Conflict> remaining = ruleEngine.validate(repaired.candidate(), assembled.ruleContext());
    List<Conflict> history = resolvedHistory(conflicts, remaining);
    return versionStore.createCompletedSuccessor(
        current,
        repaired.candidate(),
        history,
        status(remaining),
        "AUTO_RULE_CONFLICT",
        current.replanInput(),
        repaired.changeSummary(),
        true);
  }

  private PlanCandidate preserveLockedItems(
      PlanCandidate generated, TravelPlan previous, List<UUID> lockedItemIds) {
    Set<UUID> locked = Set.copyOf(lockedItemIds);
    if (locked.isEmpty()) {
      return generated;
    }
    Map<UUID, ItineraryItem> previousItems = items(previous.days());
    List<DayPlan> days =
        generated.days().stream()
            .map(
                day ->
                    new DayPlan(
                        day.dayNumber(),
                        day.date(),
                        day.title(),
                        day.items().stream()
                            .map(
                                item ->
                                    locked.contains(item.itemId())
                                        ? previousItems.getOrDefault(item.itemId(), item)
                                        : item)
                            .toList()))
            .toList();
    return generated.withDaysAndBudget(days, generated.budgetSummary(), generated.summary());
  }

  private static RuleContext adjustedContext(RuleContext context, ReplanInputSnapshot replanInput) {
    return new RuleContext(
        context.requirement(),
        context.zoneId(),
        context.cityArrival(),
        context.cityDeparture(),
        context.operatingWindows(),
        context.requiredCommuteMinutes(),
        context.categoryLimits(),
        context.confirmedBookingTypes(),
        context.itemAreas(),
        replanInput.adjustedPace() == null ? context.pace() : replanInput.adjustedPace());
  }

  private static Map<UUID, ItineraryItem> items(List<DayPlan> days) {
    Map<UUID, ItineraryItem> items = new LinkedHashMap<>();
    days.forEach(day -> day.items().forEach(item -> items.put(item.itemId(), item)));
    return Map.copyOf(items);
  }

  private static List<Conflict> resolvedHistory(List<Conflict> original, List<Conflict> remaining) {
    Set<String> remainingCodes = new LinkedHashSet<>();
    remaining.forEach(conflict -> remainingCodes.add(conflict.code()));
    List<Conflict> history = new ArrayList<>();
    for (Conflict conflict : original) {
      history.add(
          remainingCodes.contains(conflict.code())
              ? conflict
              : new Conflict(
                  conflict.conflictId(),
                  conflict.code(),
                  conflict.type(),
                  conflict.severity(),
                  conflict.message(),
                  conflict.fieldPath(),
                  conflict.relatedItemIds(),
                  conflict.autoFixable(),
                  ConflictStatus.RESOLVED));
    }
    Set<String> originalCodes = new LinkedHashSet<>();
    original.forEach(conflict -> originalCodes.add(conflict.code()));
    remaining.stream()
        .filter(conflict -> !originalCodes.contains(conflict.code()))
        .forEach(history::add);
    return List.copyOf(history);
  }

  private static PlanStatus status(List<Conflict> conflicts) {
    boolean openError =
        conflicts.stream()
            .anyMatch(
                conflict ->
                    conflict.status() == ConflictStatus.OPEN
                        && conflict.severity() == Severity.ERROR);
    return openError ? PlanStatus.PARTIAL : PlanStatus.READY;
  }

  private static List<AgentEvidence> mergeEvidence(
      List<AgentEvidence> first, List<AgentEvidence> second) {
    Map<String, AgentEvidence> merged = new LinkedHashMap<>();
    first.forEach(item -> merged.putIfAbsent(item.sourceId(), item));
    second.forEach(item -> merged.putIfAbsent(item.sourceId(), item));
    return List.copyOf(merged.values());
  }

  private static String requestText(PlanningData data) {
    ReplanInputSnapshot replan = data.plan().replanInput();
    String request =
        "Plan "
            + data.requirement().destinations().getFirst().name()
            + " from "
            + data.requirement().startDate()
            + " to "
            + data.requirement().endDate()
            + " for "
            + data.requirement().travelers().size()
            + " travelers with budget "
            + (replan.adjustedTotalBudget() == null
                ? data.requirement().totalBudget()
                : replan.adjustedTotalBudget())
            + " "
            + data.requirement().currency()
            + ".";
    return replan.naturalLanguageRequest().isBlank()
        ? request
        : request + " Modification: " + replan.naturalLanguageRequest();
  }

  private void fail(UUID taskId, UUID planId, RuntimeException exception) {
    String code =
        exception instanceof PlanningWorkflowException workflowException
            ? workflowException.code()
            : exception instanceof AsyncOperationException asyncException
                ? asyncException.code()
                : "PLAN_GENERATION_FAILED";
    log.warn(
        "Planning workflow failed planId={} taskId={} code={} exception={}",
        planId,
        taskId,
        code,
        exception.getClass().getSimpleName());
    try {
      if (taskService.get(taskId).status() == TaskStatus.CANCELLED) {
        return;
      }
      versionStore.fail(planId, code);
      if (!taskService.get(taskId).status().isTerminal()) {
        taskService.fail(
            taskId,
            code,
            "Travel planning failed at a recoverable workflow boundary. Code: " + code,
            false);
      }
    } catch (RuntimeException persistenceFailure) {
      log.warn(
          "Planning failure state could not be persisted planId={} taskId={} exception={}",
          planId,
          taskId,
          persistenceFailure.getClass().getSimpleName());
    }
  }

  private static final class PlanningWorkflowException extends RuntimeException {

    private final String code;

    private PlanningWorkflowException(String code) {
      super(code);
      this.code = code;
    }

    private String code() {
      return code;
    }
  }

  private void ensureActive(UUID taskId) {
    if (taskService.isCancellationRequested(taskId)) {
      throw new TaskCancelledSignal();
    }
  }

  private static final class TaskCancelledSignal extends RuntimeException {}

  private record ValidatedCandidate(
      UUID familyId, AssembledCandidate assembled, List<Conflict> conflicts) {}
}
