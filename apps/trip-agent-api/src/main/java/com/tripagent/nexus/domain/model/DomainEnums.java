package com.tripagent.nexus.domain.model;

import java.util.EnumSet;
import java.util.Set;

public final class DomainEnums {

  private DomainEnums() {}

  public enum Pace {
    RELAXED,
    BALANCED,
    INTENSIVE
  }

  public enum TransportMode {
    WALK,
    TRANSIT,
    TAXI,
    CAR,
    BIKE,
    RAIL,
    FLIGHT,
    BUS,
    FERRY
  }

  public enum BookingType {
    FLIGHT,
    HOTEL,
    TICKET,
    TRAIN,
    OTHER
  }

  public enum TravelerType {
    ADULT,
    CHILD,
    INFANT,
    SENIOR
  }

  public enum TripStatus {
    DRAFT,
    READY,
    ARCHIVED
  }

  public enum DocumentStatus {
    QUEUED,
    PARSING,
    NEEDS_REVIEW,
    PARSED,
    FAILED,
    CANCELLED
  }

  public enum BookingReviewStatus {
    AUTO_ACCEPTED,
    NEEDS_REVIEW,
    CORRECTED
  }

  public enum ExtractedFieldStatus {
    EXTRACTED,
    LOW_CONFIDENCE,
    MISSING,
    CORRECTED
  }

  public enum PlanStatus {
    GENERATING,
    READY,
    PARTIAL,
    FAILED,
    CONFIRMED,
    CANCELLED
  }

  public enum PlanStrategy {
    BUDGET,
    BALANCED,
    COMFORT,
    LOW_TRANSIT,
    CUSTOM
  }

  public enum TaskType {
    DOCUMENT_PARSE,
    PLAN_GENERATION,
    PLAN_REPLAN,
    EVALUATION_REPLAY
  }

  public enum GuardedActionType {
    ORDER_CREATE,
    PAYMENT,
    CANCELLATION,
    REAL_COST
  }

  public enum GuardedActionStatus {
    PENDING_CONFIRMATION,
    CONFIRMED,
    REJECTED,
    EXPIRED,
    EXECUTED
  }

  public enum ActionAuditEvent {
    PROPOSED,
    CONFIRMED,
    REJECTED,
    EXPIRED,
    EXECUTED
  }

  public enum EvaluationRunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
  }

  public enum EventDeliveryStatus {
    PENDING,
    PUBLISHED,
    DEAD_LETTERED
  }

  public enum InboxStatus {
    PROCESSING,
    RETRYABLE,
    COMPLETED,
    FAILED
  }

  public enum DeadLetterStatus {
    PENDING,
    REPLAYED
  }

  public enum TaskStatus {
    CREATED,
    QUEUED,
    RUNNING,
    WAITING_CONFIRMATION,
    SUCCEEDED,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED;

    private static final Set<TaskStatus> TERMINAL =
        EnumSet.of(SUCCEEDED, PARTIAL_SUCCESS, FAILED, CANCELLED);

    public boolean isTerminal() {
      return TERMINAL.contains(this);
    }

    public boolean canTransitionTo(TaskStatus next) {
      return switch (this) {
        case CREATED -> EnumSet.of(QUEUED, FAILED, CANCELLED).contains(next);
        case QUEUED -> EnumSet.of(RUNNING, FAILED, CANCELLED).contains(next);
        case RUNNING ->
            EnumSet.of(WAITING_CONFIRMATION, SUCCEEDED, PARTIAL_SUCCESS, FAILED, CANCELLED)
                .contains(next);
        case WAITING_CONFIRMATION ->
            EnumSet.of(RUNNING, SUCCEEDED, PARTIAL_SUCCESS, FAILED, CANCELLED).contains(next);
        case SUCCEEDED, PARTIAL_SUCCESS, FAILED, CANCELLED -> false;
      };
    }
  }

  public enum ItemType {
    TRANSPORT,
    ACCOMMODATION,
    ATTRACTION,
    DINING,
    FREE_TIME,
    OTHER
  }

  public enum ConflictType {
    TIME,
    BUDGET,
    LOCATION,
    AVAILABILITY,
    PREFERENCE,
    EVIDENCE
  }

  public enum Severity {
    ERROR,
    WARNING,
    INFO
  }

  public enum ConflictStatus {
    OPEN,
    RESOLVED,
    ACCEPTED
  }

  public enum EvidenceSourceType {
    PUBLIC_KNOWLEDGE,
    USER_MEMORY,
    DOCUMENT,
    PROVIDER,
    GRAPH
  }
}
