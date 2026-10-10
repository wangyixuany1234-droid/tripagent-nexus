package com.tripagent.nexus.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.application.planning.PlanningCommandService;
import com.tripagent.nexus.application.planning.TravelPlanMapper;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.controller.dto.ApiModels.ConfirmPlanRequest;
import com.tripagent.nexus.controller.dto.ApiModels.CreateTravelPlanRequest;
import com.tripagent.nexus.controller.dto.ApiModels.ReplanRequest;
import com.tripagent.nexus.controller.dto.ApiModels.TaskAccepted;
import com.tripagent.nexus.domain.model.BudgetSummary;
import com.tripagent.nexus.domain.model.Conflict;
import com.tripagent.nexus.domain.model.DayPlan;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStatus;
import com.tripagent.nexus.domain.model.DomainEnums.PlanStrategy;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.domain.model.ItineraryItem;
import com.tripagent.nexus.domain.model.PlanComparison;
import com.tripagent.nexus.domain.model.ReplanInputSnapshot;
import com.tripagent.nexus.domain.model.TravelPlan;
import com.tripagent.nexus.guardrail.PromptInjectionGuard;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelPlanEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelRequirementEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelPlanRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelRequirementRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanApplicationService {

  private final TravelPlanRepository planRepository;
  private final TravelRequirementRepository requirementRepository;
  private final TripApplicationService tripService;
  private final TaskApplicationService taskService;
  private final ObjectMapper objectMapper;
  private final TravelPlanMapper planMapper;
  private final PlanningCommandService planningCommandService;
  private final Clock clock;
  private final PromptInjectionGuard promptInjectionGuard;

  public PlanApplicationService(
      TravelPlanRepository planRepository,
      TravelRequirementRepository requirementRepository,
      TripApplicationService tripService,
      TaskApplicationService taskService,
      ObjectMapper objectMapper,
      TravelPlanMapper planMapper,
      PlanningCommandService planningCommandService,
      Clock clock,
      PromptInjectionGuard promptInjectionGuard) {
    this.planRepository = planRepository;
    this.requirementRepository = requirementRepository;
    this.tripService = tripService;
    this.taskService = taskService;
    this.objectMapper = objectMapper;
    this.planMapper = planMapper;
    this.planningCommandService = planningCommandService;
    this.clock = clock;
    this.promptInjectionGuard = promptInjectionGuard;
  }

  @Transactional
  public TaskAccepted create(CreateTravelPlanRequest request) {
    promptInjectionGuard.requireSafe(request.title());
    tripService.requireTrip(request.tripId());
    TravelRequirementEntity requirement = requireRequirement(request.tripId());
    UUID familyId = UUID.randomUUID();
    int version = 1;
    String title =
        request.title() == null || request.title().isBlank()
            ? "Balanced travel plan"
            : request.title();
    TravelPlanEntity plan =
        newPlan(
            request.tripId(),
            familyId,
            null,
            "balanced",
            PlanStrategy.BALANCED,
            version,
            title,
            "INITIAL_GENERATION",
            ReplanInputSnapshot.empty(),
            false,
            requirement);
    planRepository.save(plan);
    TaskAccepted task = taskService.create(TaskType.PLAN_GENERATION, plan.getPlanId());
    planningCommandService.schedule(task, TaskType.PLAN_GENERATION);
    return task;
  }

  @Transactional(readOnly = true)
  public TravelPlan get(UUID planId) {
    return planMapper.toDomain(requirePlan(planId));
  }

  @Transactional(readOnly = true)
  public List<TravelPlan> list(UUID tripId) {
    tripService.requireTrip(tripId);
    return planRepository.findByTripIdOrderByCreatedAtDesc(tripId).stream()
        .map(planMapper::toDomain)
        .toList();
  }

  @Transactional
  public TaskAccepted replan(UUID planId, ReplanRequest request) {
    validatePromptInputs(request);
    TravelPlanEntity previous = requirePlan(planId);
    if (previous.getStatus() == PlanStatus.GENERATING
        || previous.getStatus() == PlanStatus.FAILED
        || previous.getStatus() == PlanStatus.CANCELLED) {
      throw new ApiException(
          "PLAN_NOT_REPLANNABLE", HttpStatus.CONFLICT, "Only a generated plan can be replanned.");
    }
    validateReplanItems(previous, request);
    TravelRequirementEntity requirement = requireRequirement(previous.getTripId());
    int version =
        planRepository
                .findMaxVersionByPlanFamilyId(previous.getPlanFamilyId())
                .orElse(previous.getVersion())
            + 1;
    ReplanInputSnapshot snapshot = snapshot(request);
    TravelPlanEntity plan =
        newPlan(
            previous.getTripId(),
            previous.getPlanFamilyId(),
            previous.getPlanId(),
            previous.getAlternativeKey(),
            previous.getStrategy(),
            version,
            previous.getTitle() + " v" + version,
            request.reason(),
            snapshot,
            false,
            requirement);
    planRepository.save(plan);
    TaskAccepted task = taskService.create(TaskType.PLAN_REPLAN, plan.getPlanId());
    planningCommandService.schedule(task, TaskType.PLAN_REPLAN);
    return task;
  }

  @Transactional(readOnly = true)
  public PlanComparison compare(UUID planId, UUID otherPlanId) {
    TravelPlan from = planMapper.toDomain(requirePlan(planId));
    TravelPlan to = planMapper.toDomain(requirePlan(otherPlanId));
    if (!from.planFamilyId().equals(to.planFamilyId())) {
      throw new ApiException(
          "PLAN_FAMILY_MISMATCH",
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Only versions from the same plan family can be compared.");
    }
    Map<UUID, ItineraryItem> fromItems = itemMap(from.days());
    Map<UUID, ItineraryItem> toItems = itemMap(to.days());
    List<UUID> added = toItems.keySet().stream().filter(id -> !fromItems.containsKey(id)).toList();
    List<UUID> removed =
        fromItems.keySet().stream().filter(id -> !toItems.containsKey(id)).toList();
    List<UUID> changed =
        toItems.entrySet().stream()
            .filter(
                entry ->
                    fromItems.containsKey(entry.getKey())
                        && !fromItems.get(entry.getKey()).equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .toList();
    Set<String> fromOpen = openConflictCodes(from.conflicts());
    Set<String> toOpen = openConflictCodes(to.conflicts());
    List<String> resolved = fromOpen.stream().filter(code -> !toOpen.contains(code)).toList();
    List<String> introduced = toOpen.stream().filter(code -> !fromOpen.contains(code)).toList();
    BigDecimal budgetDelta =
        to.budgetSummary()
            .estimatedTotal()
            .subtract(from.budgetSummary().estimatedTotal())
            .setScale(4, RoundingMode.HALF_UP);
    return new PlanComparison(
        from.planFamilyId(),
        from.planId(),
        to.planId(),
        from.version(),
        to.version(),
        budgetDelta,
        added,
        removed,
        changed,
        resolved,
        introduced,
        "Budget changed by "
            + budgetDelta
            + " "
            + to.budgetSummary().currency()
            + "; "
            + resolved.size()
            + " conflict(s) resolved.");
  }

  @Transactional
  public TravelPlan confirm(UUID planId, ConfirmPlanRequest request) {
    TravelPlanEntity plan = requirePlan(planId);
    try {
      plan.confirm(request.expectedVersion(), clock.instant());
    } catch (IllegalStateException exception) {
      throw new ApiException(
          exception.getMessage(),
          HttpStatus.CONFLICT,
          "The plan cannot be confirmed in its current state or version.");
    }
    return planMapper.toDomain(planRepository.save(plan));
  }

  private TravelPlanEntity newPlan(
      UUID tripId,
      UUID planFamilyId,
      UUID previousPlanId,
      String alternativeKey,
      PlanStrategy strategy,
      int version,
      String title,
      String triggerReason,
      ReplanInputSnapshot replanInput,
      boolean autoReplanned,
      TravelRequirementEntity requirement) {
    BudgetSummary budget =
        new BudgetSummary(
            requirement.getTotalBudget(),
            BigDecimal.ZERO.setScale(4),
            requirement.getTotalBudget(),
            requirement.getCurrency(),
            List.of());
    Instant now = clock.instant();
    return new TravelPlanEntity(
        UUID.randomUUID(),
        tripId,
        planFamilyId,
        previousPlanId,
        alternativeKey,
        strategy,
        version,
        title,
        "",
        objectMapper.valueToTree(List.of()),
        objectMapper.valueToTree(budget),
        objectMapper.valueToTree(List.of()),
        objectMapper.valueToTree(List.of()),
        triggerReason,
        objectMapper.valueToTree(replanInput),
        "",
        autoReplanned,
        now);
  }

  private TravelRequirementEntity requireRequirement(UUID tripId) {
    return requirementRepository
        .findById(tripId)
        .orElseThrow(
            () ->
                new ApiException(
                    "TRAVEL_REQUIREMENT_NOT_FOUND",
                    HttpStatus.CONFLICT,
                    "A travel requirement is required before planning."));
  }

  private TravelPlanEntity requirePlan(UUID planId) {
    return planRepository
        .findById(planId)
        .orElseThrow(
            () ->
                new ApiException(
                    "PLAN_NOT_FOUND", HttpStatus.NOT_FOUND, "Travel plan was not found."));
  }

  private void validateReplanItems(TravelPlanEntity previous, ReplanRequest request) {
    Set<UUID> locked = new HashSet<>(values(request.lockedItemIds()));
    Set<UUID> removed = new HashSet<>(values(request.removedItemIds()));
    Set<UUID> overlap = new HashSet<>(locked);
    overlap.retainAll(removed);
    if (!overlap.isEmpty()) {
      throw new ApiException(
          "REPLAN_ITEM_DIRECTIVE_CONFLICT",
          HttpStatus.UNPROCESSABLE_ENTITY,
          "An itinerary item cannot be both locked and removed.");
    }
    Set<UUID> existing = itemMap(planMapper.toDomain(previous).days()).keySet();
    if (!existing.containsAll(locked) || !existing.containsAll(removed)) {
      throw new ApiException(
          "REPLAN_ITEM_NOT_FOUND",
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Every locked or removed item must belong to the selected plan version.");
    }
  }

  private static ReplanInputSnapshot snapshot(ReplanRequest request) {
    String naturalLanguage =
        request.naturalLanguageRequest() == null || request.naturalLanguageRequest().isBlank()
            ? request.freeTextConstraints()
            : request.naturalLanguageRequest();
    return new ReplanInputSnapshot(
        values(request.lockedItemIds()),
        values(request.removedItemIds()),
        request.adjustedTotalBudget(),
        request.adjustedPace(),
        values(request.addedPreferences()),
        naturalLanguage);
  }

  private static Map<UUID, ItineraryItem> itemMap(List<DayPlan> days) {
    Map<UUID, ItineraryItem> items = new LinkedHashMap<>();
    days.forEach(day -> day.items().forEach(item -> items.put(item.itemId(), item)));
    return Map.copyOf(items);
  }

  private static Set<String> openConflictCodes(List<Conflict> conflicts) {
    Set<String> codes = new HashSet<>();
    conflicts.stream()
        .filter(
            conflict ->
                conflict.status()
                    == com.tripagent.nexus.domain.model.DomainEnums.ConflictStatus.OPEN)
        .forEach(conflict -> codes.add(conflict.code()));
    return Set.copyOf(codes);
  }

  private static <T> List<T> values(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  private void validatePromptInputs(ReplanRequest request) {
    promptInjectionGuard.requireSafe(request.reason());
    promptInjectionGuard.requireSafe(request.freeTextConstraints());
    promptInjectionGuard.requireSafe(request.naturalLanguageRequest());
    values(request.addedPreferences()).forEach(promptInjectionGuard::requireSafe);
  }
}
