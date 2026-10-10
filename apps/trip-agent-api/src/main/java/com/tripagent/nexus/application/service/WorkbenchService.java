package com.tripagent.nexus.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.Phase14Models.WorkbenchTask;
import com.tripagent.nexus.infrastructure.persistence.entity.DocumentEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TaskRecordEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelPlanEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelRequirementEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TripEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.DocumentRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TaskRecordRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelPlanRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelRequirementRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TripRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkbenchService {

  private final TripRepository tripRepository;
  private final TravelRequirementRepository requirementRepository;
  private final DocumentRepository documentRepository;
  private final TravelPlanRepository planRepository;
  private final TaskRecordRepository taskRepository;
  private final ObjectMapper objectMapper;

  public WorkbenchService(
      TripRepository tripRepository,
      TravelRequirementRepository requirementRepository,
      DocumentRepository documentRepository,
      TravelPlanRepository planRepository,
      TaskRecordRepository taskRepository,
      ObjectMapper objectMapper) {
    this.tripRepository = tripRepository;
    this.requirementRepository = requirementRepository;
    this.documentRepository = documentRepository;
    this.planRepository = planRepository;
    this.taskRepository = taskRepository;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public List<WorkbenchTask> list(
      UUID userId, String destination, LocalDate startDate, LocalDate endDate, TaskStatus status) {
    List<WorkbenchTask> items = new ArrayList<>();
    for (TripEntity trip : tripRepository.findByUserIdOrderByUpdatedAtDesc(userId)) {
      TravelRequirementEntity requirement =
          requirementRepository.findById(trip.getTripId()).orElse(null);
      String primaryDestination = destination(requirement);
      if (!matchesDestination(primaryDestination, destination)
          || !overlaps(requirement, startDate, endDate)) {
        continue;
      }

      Map<UUID, UUID> resources = new HashMap<>();
      for (DocumentEntity document : documentRepository.findByTripId(trip.getTripId())) {
        resources.put(document.getDocumentId(), trip.getTripId());
      }
      for (TravelPlanEntity plan :
          planRepository.findByTripIdOrderByCreatedAtDesc(trip.getTripId())) {
        resources.put(plan.getPlanId(), trip.getTripId());
      }
      if (resources.isEmpty()) {
        continue;
      }
      for (TaskRecordEntity task :
          taskRepository.findByResourceIdInOrderByUpdatedAtDesc(resources.keySet())) {
        if (status == null || task.getStatus() == status) {
          items.add(toItem(task, trip, requirement, primaryDestination));
        }
      }
    }
    return items.stream()
        .sorted(Comparator.comparing(WorkbenchTask::updatedAt).reversed())
        .toList();
  }

  private WorkbenchTask toItem(
      TaskRecordEntity task,
      TripEntity trip,
      TravelRequirementEntity requirement,
      String destination) {
    return new WorkbenchTask(
        task.getTaskId(),
        trip.getTripId(),
        firstResultId(task.getResultResourceIds()),
        trip.getTitle(),
        destination,
        requirement == null ? null : requirement.getStartDate(),
        requirement == null ? null : requirement.getEndDate(),
        task.getType(),
        task.getStatus(),
        task.getProgress(),
        task.getCreatedAt(),
        task.getUpdatedAt());
  }

  private UUID firstResultId(JsonNode resultIds) {
    if (resultIds == null || !resultIds.isArray() || resultIds.isEmpty()) {
      return null;
    }
    return objectMapper.convertValue(resultIds.get(0), UUID.class);
  }

  private static String destination(TravelRequirementEntity requirement) {
    if (requirement == null
        || !requirement.getDestinations().isArray()
        || requirement.getDestinations().isEmpty()) {
      return "";
    }
    return requirement.getDestinations().get(0).path("name").asText("");
  }

  private static boolean matchesDestination(String actual, String expected) {
    return expected == null
        || expected.isBlank()
        || actual.toLowerCase(Locale.ROOT).contains(expected.trim().toLowerCase(Locale.ROOT));
  }

  private static boolean overlaps(
      TravelRequirementEntity requirement, LocalDate startDate, LocalDate endDate) {
    if (requirement == null) {
      return startDate == null && endDate == null;
    }
    return (startDate == null || !requirement.getEndDate().isBefore(startDate))
        && (endDate == null || !requirement.getStartDate().isAfter(endDate));
  }
}
