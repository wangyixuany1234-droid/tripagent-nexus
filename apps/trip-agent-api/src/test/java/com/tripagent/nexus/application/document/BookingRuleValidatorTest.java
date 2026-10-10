package com.tripagent.nexus.application.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tripagent.nexus.domain.model.DomainEnums.BookingType;
import com.tripagent.nexus.domain.model.ParsedBooking.ValidationIssue;
import com.tripagent.nexus.infrastructure.config.DocumentProcessingProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.ParsedBookingEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelRequirementEntity;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult.ExtractedBooking;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult.FieldEvidence;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BookingRuleValidatorTest {

  private BookingRuleValidator validator;

  @BeforeEach
  void setUp() {
    DocumentProcessingProperties properties = new DocumentProcessingProperties();
    properties.setCriticalConfidenceThreshold(new BigDecimal("0.7500"));
    validator = new BookingRuleValidator(properties);
  }

  @Test
  void detectsDateMoneyAndConfidenceViolations() {
    TravelRequirementEntity requirement = mock(TravelRequirementEntity.class);
    when(requirement.getStartDate()).thenReturn(LocalDate.parse("2026-10-01"));
    when(requirement.getEndDate()).thenReturn(LocalDate.parse("2026-10-05"));
    ExtractedBooking booking =
        booking(
            "NEW-1",
            Instant.parse("2026-10-07T10:00:00Z"),
            Instant.parse("2026-10-06T10:00:00Z"),
            null,
            null,
            new BigDecimal("0.5000"));

    List<String> codes =
        validator
            .validate(result(booking), requirement, ZoneId.of("Asia/Tokyo"), List.of())
            .getFirst()
            .issues()
            .stream()
            .map(ValidationIssue::code)
            .toList();

    assertThat(codes)
        .contains(
            "END_BEFORE_START",
            "OUTSIDE_TRIP_DATES",
            "AMOUNT_OR_CURRENCY_MISSING",
            "LOW_CONFIDENCE_CRITICAL_FIELD");
  }

  @Test
  void detectsDuplicateConfirmationAndExclusiveTransportOverlap() {
    ParsedBookingEntity existing = mock(ParsedBookingEntity.class);
    when(existing.getType()).thenReturn(BookingType.FLIGHT);
    when(existing.getConfirmationNumber()).thenReturn("DUP-1");
    when(existing.getStartTime()).thenReturn(Instant.parse("2026-10-01T01:00:00Z"));
    when(existing.getEndTime()).thenReturn(Instant.parse("2026-10-01T04:00:00Z"));
    ExtractedBooking booking =
        booking(
            "dup-1",
            Instant.parse("2026-10-01T02:00:00Z"),
            Instant.parse("2026-10-01T05:00:00Z"),
            new BigDecimal("1000.0000"),
            "CNY",
            new BigDecimal("0.9500"));

    List<String> codes =
        validator
            .validate(result(booking), null, ZoneId.of("Asia/Tokyo"), List.of(existing))
            .getFirst()
            .issues()
            .stream()
            .map(ValidationIssue::code)
            .toList();

    assertThat(codes).contains("DUPLICATE_CONFIRMATION", "MUTUALLY_EXCLUSIVE_TIME_OVERLAP");
  }

  @Test
  void usesTheTripTimeZoneAtUtcDateBoundaries() {
    TravelRequirementEntity requirement = mock(TravelRequirementEntity.class);
    when(requirement.getStartDate()).thenReturn(LocalDate.parse("2026-10-01"));
    when(requirement.getEndDate()).thenReturn(LocalDate.parse("2026-10-05"));
    ExtractedBooking insideTokyoDates =
        booking(
            "TZ-INSIDE",
            Instant.parse("2026-09-30T15:30:00Z"),
            Instant.parse("2026-09-30T16:30:00Z"),
            new BigDecimal("100.0000"),
            "JPY",
            new BigDecimal("0.9500"));
    ExtractedBooking outsideTokyoDates =
        booking(
            "TZ-OUTSIDE",
            Instant.parse("2026-10-05T15:30:00Z"),
            Instant.parse("2026-10-05T16:30:00Z"),
            new BigDecimal("100.0000"),
            "JPY",
            new BigDecimal("0.9500"));

    assertThat(
            validator
                .validate(result(insideTokyoDates), requirement, ZoneId.of("Asia/Tokyo"), List.of())
                .getFirst()
                .issues())
        .isEmpty();
    assertThat(
            validator
                .validate(
                    result(outsideTokyoDates), requirement, ZoneId.of("Asia/Tokyo"), List.of())
                .getFirst()
                .issues())
        .extracting(ValidationIssue::code)
        .contains("OUTSIDE_TRIP_DATES");
  }

  @Test
  void detectsDuplicateOrdersWithinOneExtractionResult() {
    ExtractedBooking first =
        booking(
            "SAME-ORDER",
            Instant.parse("2026-10-01T01:00:00Z"),
            Instant.parse("2026-10-01T04:00:00Z"),
            new BigDecimal("1000.0000"),
            "CNY",
            new BigDecimal("0.9500"));
    ExtractedBooking duplicate =
        booking(
            "same-order",
            Instant.parse("2026-10-01T05:00:00Z"),
            Instant.parse("2026-10-01T08:00:00Z"),
            new BigDecimal("1000.0000"),
            "CNY",
            new BigDecimal("0.9500"));

    List<BookingRuleValidator.ValidatedBooking> validated =
        validator.validate(
            new BookingExtractionResult(
                List.of(first, duplicate),
                List.of(),
                new BigDecimal("0.9000"),
                "MOCK",
                "test",
                "mock"),
            null,
            ZoneId.of("Asia/Shanghai"),
            List.of());

    assertThat(validated.getFirst().issues())
        .extracting(ValidationIssue::code)
        .doesNotContain("DUPLICATE_CONFIRMATION");
    assertThat(validated.get(1).issues())
        .extracting(ValidationIssue::code)
        .contains("DUPLICATE_CONFIRMATION");
  }

  private static ExtractedBooking booking(
      String confirmation,
      Instant start,
      Instant end,
      BigDecimal amount,
      String currency,
      BigDecimal startConfidence) {
    return new ExtractedBooking(
        BookingType.FLIGHT,
        "Example Air",
        "Shanghai",
        "Tokyo",
        null,
        null,
        "MU1234",
        confirmation,
        start,
        end,
        1,
        amount,
        currency,
        new BigDecimal("0.9000"),
        List.of(),
        List.of(
            new FieldEvidence("startTime", startConfidence, "departure", null),
            new FieldEvidence("endTime", new BigDecimal("0.9500"), "arrival", null),
            new FieldEvidence("confirmationNumber", new BigDecimal("0.9500"), confirmation, null)));
  }

  private static BookingExtractionResult result(ExtractedBooking booking) {
    return new BookingExtractionResult(
        List.of(booking), List.of(), new BigDecimal("0.9000"), "MOCK", "test", "mock");
  }
}
