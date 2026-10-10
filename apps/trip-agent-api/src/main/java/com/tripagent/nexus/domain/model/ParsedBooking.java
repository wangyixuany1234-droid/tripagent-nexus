package com.tripagent.nexus.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tripagent.nexus.domain.model.DomainEnums.BookingReviewStatus;
import com.tripagent.nexus.domain.model.DomainEnums.BookingType;
import com.tripagent.nexus.domain.model.DomainEnums.ExtractedFieldStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ParsedBooking(
    UUID bookingId,
    BookingType type,
    UUID sourceDocumentId,
    @JsonInclude(JsonInclude.Include.NON_NULL) String provider,
    @JsonInclude(JsonInclude.Include.NON_NULL) String origin,
    @JsonInclude(JsonInclude.Include.NON_NULL) String destination,
    @JsonInclude(JsonInclude.Include.NON_NULL) String hotelName,
    @JsonInclude(JsonInclude.Include.NON_NULL) String hotelAddress,
    @JsonInclude(JsonInclude.Include.NON_NULL) String transportNumber,
    @JsonInclude(JsonInclude.Include.NON_NULL) String confirmationNumber,
    @JsonInclude(JsonInclude.Include.NON_NULL) Instant startTime,
    @JsonInclude(JsonInclude.Include.NON_NULL) Instant endTime,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer travelerCount,
    @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal amount,
    @JsonInclude(JsonInclude.Include.NON_NULL) String currency,
    BigDecimal confidence,
    List<String> missingFields,
    BookingReviewStatus reviewStatus,
    List<FieldEvidence> fieldEvidence,
    List<ValidationIssue> validationIssues,
    List<CorrectionAudit> corrections) {

  public record EvidenceRegion(
      int page, BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {}

  public record FieldEvidence(
      String field,
      BigDecimal confidence,
      ExtractedFieldStatus status,
      @JsonInclude(JsonInclude.Include.NON_NULL) String textEvidence,
      @JsonInclude(JsonInclude.Include.NON_NULL) EvidenceRegion region) {}

  public record ValidationIssue(String code, String field, String message) {}

  public record CorrectionAudit(
      UUID auditId,
      String field,
      String originalValue,
      String correctedValue,
      String actorId,
      @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
      Instant correctedAt) {}
}
