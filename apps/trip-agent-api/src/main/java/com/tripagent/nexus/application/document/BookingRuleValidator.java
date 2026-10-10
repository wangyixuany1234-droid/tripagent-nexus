package com.tripagent.nexus.application.document;

import com.tripagent.nexus.domain.model.DomainEnums.BookingReviewStatus;
import com.tripagent.nexus.domain.model.DomainEnums.BookingType;
import com.tripagent.nexus.domain.model.DomainEnums.ExtractedFieldStatus;
import com.tripagent.nexus.domain.model.ParsedBooking.FieldEvidence;
import com.tripagent.nexus.domain.model.ParsedBooking.ValidationIssue;
import com.tripagent.nexus.infrastructure.config.DocumentProcessingProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.ParsedBookingEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelRequirementEntity;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class BookingRuleValidator {

  private static final Set<String> ALLOWED_FIELDS =
      Set.of(
          "type",
          "provider",
          "origin",
          "destination",
          "hotelName",
          "hotelAddress",
          "transportNumber",
          "startTime",
          "endTime",
          "travelerCount",
          "amount",
          "currency",
          "confirmationNumber");

  private final BigDecimal confidenceThreshold;

  public BookingRuleValidator(DocumentProcessingProperties properties) {
    this.confidenceThreshold = properties.getCriticalConfidenceThreshold();
  }

  public List<ValidatedBooking> validate(
      BookingExtractionResult result,
      TravelRequirementEntity requirement,
      ZoneId tripZone,
      List<ParsedBookingEntity> existingBookings) {
    List<ValidatedBooking> validated = new ArrayList<>();
    Set<String> seenConfirmations = new HashSet<>();
    for (BookingExtractionResult.ExtractedBooking booking : result.bookings()) {
      List<ValidationIssue> issues = new ArrayList<>();
      validateTimes(booking, requirement, tripZone, issues);
      validateMoney(booking, issues);
      validateConfirmation(booking, existingBookings, seenConfirmations, issues);
      validateOverlap(booking, existingBookings, result.bookings(), issues);
      List<FieldEvidence> evidence = validateEvidence(booking, issues);
      BookingReviewStatus status =
          issues.isEmpty() ? BookingReviewStatus.AUTO_ACCEPTED : BookingReviewStatus.NEEDS_REVIEW;
      validated.add(new ValidatedBooking(booking, status, evidence, List.copyOf(issues)));
    }
    return List.copyOf(validated);
  }

  private static void validateTimes(
      BookingExtractionResult.ExtractedBooking booking,
      TravelRequirementEntity requirement,
      ZoneId tripZone,
      List<ValidationIssue> issues) {
    if (booking.startTime() != null
        && booking.endTime() != null
        && booking.endTime().isBefore(booking.startTime())) {
      issues.add(issue("END_BEFORE_START", "endTime", "End time is earlier than start time."));
    }
    if (requirement == null) {
      return;
    }
    if (outside(booking.startTime(), requirement.getStartDate(), requirement.getEndDate(), tripZone)
        || outside(
            booking.endTime(), requirement.getStartDate(), requirement.getEndDate(), tripZone)) {
      issues.add(
          issue(
              "OUTSIDE_TRIP_DATES",
              "startTime",
              "The booking falls outside the configured trip dates."));
    }
  }

  private static boolean outside(
      Instant value, LocalDate startDate, LocalDate endDate, ZoneId tripZone) {
    if (value == null) {
      return false;
    }
    LocalDate date = value.atZone(tripZone).toLocalDate();
    return date.isBefore(startDate) || date.isAfter(endDate);
  }

  private static void validateMoney(
      BookingExtractionResult.ExtractedBooking booking, List<ValidationIssue> issues) {
    if (booking.amount() == null || booking.currency() == null || booking.currency().isBlank()) {
      issues.add(
          issue(
              "AMOUNT_OR_CURRENCY_MISSING",
              booking.amount() == null ? "amount" : "currency",
              "Amount and currency must both be present."));
    }
  }

  private static void validateConfirmation(
      BookingExtractionResult.ExtractedBooking booking,
      List<ParsedBookingEntity> existingBookings,
      Set<String> seenConfirmations,
      List<ValidationIssue> issues) {
    if (booking.confirmationNumber() == null || booking.confirmationNumber().isBlank()) {
      return;
    }
    String normalized = booking.confirmationNumber().trim().toUpperCase(Locale.ROOT);
    boolean exists =
        existingBookings.stream()
            .map(ParsedBookingEntity::getConfirmationNumber)
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().toUpperCase(Locale.ROOT))
            .anyMatch(normalized::equals);
    if (exists || !seenConfirmations.add(normalized)) {
      issues.add(
          issue(
              "DUPLICATE_CONFIRMATION",
              "confirmationNumber",
              "The confirmation number already exists in this trip."));
    }
  }

  private static void validateOverlap(
      BookingExtractionResult.ExtractedBooking booking,
      List<ParsedBookingEntity> existingBookings,
      List<BookingExtractionResult.ExtractedBooking> extractedBookings,
      List<ValidationIssue> issues) {
    if (!isExclusiveTransport(booking.type())
        || booking.startTime() == null
        || booking.endTime() == null) {
      return;
    }
    boolean existingOverlap =
        existingBookings.stream()
            .anyMatch(
                existing ->
                    isExclusiveTransport(existing.getType())
                        && overlaps(
                            booking.startTime(),
                            booking.endTime(),
                            existing.getStartTime(),
                            existing.getEndTime()));
    long extractedOverlap =
        extractedBookings.stream()
            .filter(candidate -> candidate != booking)
            .filter(candidate -> isExclusiveTransport(candidate.type()))
            .filter(
                candidate ->
                    overlaps(
                        booking.startTime(),
                        booking.endTime(),
                        candidate.startTime(),
                        candidate.endTime()))
            .count();
    if (existingOverlap || extractedOverlap > 0) {
      issues.add(
          issue(
              "MUTUALLY_EXCLUSIVE_TIME_OVERLAP",
              "startTime",
              "This transport booking overlaps another mutually exclusive booking."));
    }
  }

  private List<FieldEvidence> validateEvidence(
      BookingExtractionResult.ExtractedBooking booking, List<ValidationIssue> issues) {
    Set<String> critical = criticalFields(booking.type());
    List<FieldEvidence> evidence = new ArrayList<>();
    Set<String> represented = new HashSet<>();
    for (BookingExtractionResult.FieldEvidence item : booking.fieldEvidence()) {
      if (!ALLOWED_FIELDS.contains(item.field()) || !represented.add(item.field())) {
        continue;
      }
      ExtractedFieldStatus status =
          item.confidence().compareTo(confidenceThreshold) < 0 && critical.contains(item.field())
              ? ExtractedFieldStatus.LOW_CONFIDENCE
              : ExtractedFieldStatus.EXTRACTED;
      if (status == ExtractedFieldStatus.LOW_CONFIDENCE) {
        issues.add(
            issue(
                "LOW_CONFIDENCE_CRITICAL_FIELD",
                item.field(),
                "A critical extracted field is below the confidence threshold."));
      }
      evidence.add(
          new FieldEvidence(
              item.field(),
              item.confidence(),
              status,
              item.textEvidence(),
              item.region() == null
                  ? null
                  : new com.tripagent.nexus.domain.model.ParsedBooking.EvidenceRegion(
                      item.region().page(),
                      item.region().x(),
                      item.region().y(),
                      item.region().width(),
                      item.region().height())));
    }
    for (String missing : booking.missingFields()) {
      if (ALLOWED_FIELDS.contains(missing) && represented.add(missing)) {
        if (critical.contains(missing)) {
          issues.add(
              issue(
                  "LOW_CONFIDENCE_CRITICAL_FIELD",
                  missing,
                  "A critical extracted field is missing."));
        }
        evidence.add(
            new FieldEvidence(missing, BigDecimal.ZERO, ExtractedFieldStatus.MISSING, null, null));
      }
    }
    return List.copyOf(evidence);
  }

  private static Set<String> criticalFields(BookingType type) {
    return switch (type) {
      case FLIGHT, TRAIN ->
          Set.of(
              "type",
              "origin",
              "destination",
              "transportNumber",
              "startTime",
              "endTime",
              "confirmationNumber");
      case HOTEL ->
          Set.of("type", "hotelName", "hotelAddress", "startTime", "endTime", "confirmationNumber");
      case TICKET -> Set.of("type", "provider", "startTime", "confirmationNumber");
      case OTHER -> Set.of("type", "provider", "startTime");
    };
  }

  private static boolean isExclusiveTransport(BookingType type) {
    return type == BookingType.FLIGHT || type == BookingType.TRAIN;
  }

  private static boolean overlaps(
      Instant firstStart, Instant firstEnd, Instant secondStart, Instant secondEnd) {
    return secondStart != null
        && secondEnd != null
        && firstStart.isBefore(secondEnd)
        && secondStart.isBefore(firstEnd);
  }

  private static ValidationIssue issue(String code, String field, String message) {
    return new ValidationIssue(code, field, message);
  }

  public record ValidatedBooking(
      BookingExtractionResult.ExtractedBooking extraction,
      BookingReviewStatus reviewStatus,
      List<FieldEvidence> fieldEvidence,
      List<ValidationIssue> issues) {}
}
