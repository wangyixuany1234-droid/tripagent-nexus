package com.tripagent.nexus.application.security;

import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.infrastructure.persistence.entity.TaskRecordEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.DocumentRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TaskRecordRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelPlanRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TripRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceAuthorizationService {

  private final AuthenticatedActorService actors;
  private final TripRepository trips;
  private final TravelPlanRepository plans;
  private final DocumentRepository documents;
  private final TaskRecordRepository tasks;

  public ResourceAuthorizationService(
      AuthenticatedActorService actors,
      TripRepository trips,
      TravelPlanRepository plans,
      DocumentRepository documents,
      TaskRecordRepository tasks) {
    this.actors = actors;
    this.trips = trips;
    this.plans = plans;
    this.documents = documents;
    this.tasks = tasks;
  }

  public void requireSameUser(Authentication authentication, UUID requestedUserId) {
    if (!actors.isEnforced()) {
      return;
    }
    if (!actors.requireUserId(authentication).equals(requestedUserId)) {
      throw hidden();
    }
  }

  @Transactional(readOnly = true)
  public void requireTrip(Authentication authentication, UUID tripId) {
    if (!actors.isEnforced()) {
      return;
    }
    requireTripOwner(actors.requireUserId(authentication), tripId);
  }

  @Transactional(readOnly = true)
  public void requirePlan(Authentication authentication, UUID planId) {
    if (!actors.isEnforced()) {
      return;
    }
    UUID tripId =
        plans.findTripIdByPlanId(planId).orElseThrow(ResourceAuthorizationService::hidden);
    requireTripOwner(actors.requireUserId(authentication), tripId);
  }

  @Transactional(readOnly = true)
  public void requireDocument(Authentication authentication, UUID documentId) {
    if (!actors.isEnforced()) {
      return;
    }
    UUID tripId =
        documents
            .findById(documentId)
            .map(document -> document.getTripId())
            .orElseThrow(ResourceAuthorizationService::hidden);
    requireTripOwner(actors.requireUserId(authentication), tripId);
  }

  @Transactional(readOnly = true)
  public void requireTask(Authentication authentication, UUID taskId) {
    if (!actors.isEnforced()) {
      return;
    }
    TaskRecordEntity task =
        tasks.findById(taskId).orElseThrow(ResourceAuthorizationService::hidden);
    if (task.getType() == TaskType.EVALUATION_REPLAY) {
      if (!actors.isAdmin(authentication, null)) {
        throw hidden();
      }
      return;
    }
    UUID tripId =
        switch (task.getType()) {
          case DOCUMENT_PARSE ->
              documents
                  .findById(task.getResourceId())
                  .map(document -> document.getTripId())
                  .orElseThrow(ResourceAuthorizationService::hidden);
          case PLAN_GENERATION, PLAN_REPLAN ->
              plans
                  .findTripIdByPlanId(task.getResourceId())
                  .orElseThrow(ResourceAuthorizationService::hidden);
          case EVALUATION_REPLAY -> throw hidden();
        };
    requireTripOwner(actors.requireUserId(authentication), tripId);
  }

  private void requireTripOwner(UUID userId, UUID tripId) {
    if (!trips.existsByTripIdAndUserId(tripId, userId)) {
      throw hidden();
    }
  }

  private static ApiException hidden() {
    return new ApiException(
        "RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, "The requested resource was not found.");
  }
}
