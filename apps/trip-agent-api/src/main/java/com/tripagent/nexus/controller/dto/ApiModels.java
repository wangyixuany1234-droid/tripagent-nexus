package com.tripagent.nexus.controller.dto;

import com.tripagent.nexus.domain.model.AgentExecution;
import com.tripagent.nexus.domain.model.DeadLetterRecord;
import com.tripagent.nexus.domain.model.DomainEnums.GuardedActionType;
import com.tripagent.nexus.domain.model.DomainEnums.Pace;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TravelerType;
import com.tripagent.nexus.domain.model.Feedback;
import com.tripagent.nexus.domain.model.TravelPlan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ApiModels {

  private ApiModels() {}

  public record CreateTripRequest(
      @NotNull UUID userId,
      @NotBlank @Size(max = 160) String title,
      @NotBlank @Size(max = 64) String timeZone) {}

  public record PlaceInput(
      UUID placeId,
      @NotBlank @Size(max = 300) String name,
      @Size(max = 500) String address,
      @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 7) BigDecimal latitude,
      @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 7) BigDecimal longitude) {}

  public record TravelerInput(
      @NotNull UUID travelerId,
      @NotBlank @Size(max = 100) String displayName,
      @NotNull TravelerType type,
      @NotNull @Size(max = 20) List<@NotNull @Size(max = 100) String> accessibilityNeeds) {}

  public record SaveTravelRequirementRequest(
      @NotNull @Valid PlaceInput origin,
      @NotEmpty @Size(max = 20) List<@NotNull @Valid PlaceInput> destinations,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate,
      @NotEmpty @Size(max = 50) List<@NotNull @Valid TravelerInput> travelers,
      @NotNull @DecimalMin("0.0000") @Digits(integer = 15, fraction = 4) BigDecimal totalBudget,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @NotNull @Size(max = 50) List<@NotNull @Size(max = 100) String> interests,
      @NotNull Pace pace,
      @NotNull @Size(max = 50) List<@NotNull @Size(max = 200) String> mustVisit,
      @NotNull @Size(max = 50) List<@NotNull @Size(max = 200) String> avoid,
      @NotNull @Size(max = 5000) String freeTextConstraints) {}

  public record CreateTravelPlanRequest(@NotNull UUID tripId, @Size(max = 200) String title) {}

  public record ReplanRequest(
      @NotBlank @Size(max = 1000) String reason,
      @Size(max = 5000) String freeTextConstraints,
      @Size(max = 100) List<@NotNull UUID> lockedItemIds,
      @Size(max = 100) List<@NotNull UUID> removedItemIds,
      @DecimalMin("0.0000") @Digits(integer = 15, fraction = 4) BigDecimal adjustedTotalBudget,
      Pace adjustedPace,
      @Size(max = 50) List<@NotBlank @Size(max = 200) String> addedPreferences,
      @Size(max = 5000) String naturalLanguageRequest) {}

  public record ConfirmPlanRequest(@Min(1) int expectedVersion) {}

  public record CreateGuardedActionRequest(
      @NotNull GuardedActionType type,
      @NotNull UUID resourceId,
      @NotBlank @Size(max = 1000) String riskDescription,
      @NotNull @DecimalMin("0.0000") @Digits(integer = 15, fraction = 4) BigDecimal amount,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @NotNull Instant expiresAt) {}

  public record ConfirmGuardedActionRequest(
      @NotNull UUID actionId,
      @Min(0) long expectedVersion,
      boolean acknowledgedRisk,
      @NotNull @DecimalMin("0.0000") @Digits(integer = 15, fraction = 4) BigDecimal acknowledgedAmount,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}

  public record RejectGuardedActionRequest(
      @NotNull UUID actionId,
      @Min(0) long expectedVersion,
      @NotBlank @Size(max = 500) String reason) {}

  public record CreateFeedbackRequest(
      @NotNull UUID tripId,
      UUID planId,
      @Pattern(regexp = "^(PLAN|PLACE|BUDGET|EVIDENCE)$") String targetType,
      @Size(max = 200) String targetId,
      @Pattern(regexp = "^(LIKE|DISLIKE)$") String sentiment,
      @Min(1) @Max(5) int rating,
      @NotNull @Size(max = 5000) String comment,
      @NotNull @Size(max = 20) List<@NotNull @Size(max = 100) String> tags) {}

  public record SaveUserPreferenceRequest(
      @NotBlank @Size(max = 100) String category, @NotBlank @Size(max = 500) String value) {}

  public record BookingFieldCorrection(
      @NotBlank @Pattern(
              regexp =
                  "^(type|provider|origin|destination|hotelName|hotelAddress|transportNumber|startTime|endTime|travelerCount|amount|currency|confirmationNumber)$")
          String field,
      @NotNull @Size(max = 2000) String value) {}

  public record BookingCorrectionRequest(
      @NotEmpty @Size(max = 13) List<@Valid BookingFieldCorrection> corrections,
      @Size(max = 500) String reason) {}

  public record TaskAccepted(
      UUID taskId, UUID resourceId, TaskStatus status, String traceId, Instant createdAt) {}

  public record ReplayDeadLetterRequest(@NotBlank @Size(max = 500) String reason) {}

  public record CreateEvaluationRunRequest(
      @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{2,63}$") String datasetVersion,
      @Size(max = 100) List<@Pattern(regexp = "^[a-z0-9][a-z0-9._-]{2,99}$") String> caseIds) {}

  public record DeadLetterList(List<DeadLetterRecord> items) {
    public DeadLetterList {
      items = List.copyOf(items);
    }
  }

  public record TravelPlanList(List<TravelPlan> items) {}

  public record AgentExecutionList(List<AgentExecution> items) {
    public AgentExecutionList {
      items = List.copyOf(items);
    }
  }

  public record FeedbackList(List<Feedback> items) {
    public FeedbackList {
      items = List.copyOf(items);
    }
  }

  public record WorkbenchTaskList(
      List<com.tripagent.nexus.domain.model.Phase14Models.WorkbenchTask> items) {
    public WorkbenchTaskList {
      items = List.copyOf(items);
    }
  }

  public record UserPreferenceList(
      List<com.tripagent.nexus.domain.model.Phase14Models.UserPreference> items) {
    public UserPreferenceList {
      items = List.copyOf(items);
    }
  }

  public record ErrorDetail(String field, String code, String message) {}

  public record ApiError(
      String code, String message, List<ErrorDetail> details, String traceId, Instant timestamp) {}

  public record FeedbackResponse(Feedback feedback) {}
}
