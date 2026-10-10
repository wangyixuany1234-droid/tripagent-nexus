package com.tripagent.nexus.integration.model.result;

import com.tripagent.nexus.domain.model.DomainEnums.BookingType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BookingExtractionResult(
    @NotNull @Size(min = 1, max = 100) List<@Valid ExtractedBooking> bookings,
    @NotNull @Size(max = 100) List<@NotBlank String> warnings,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
    @NotBlank @Pattern(regexp = "^(DEEPSEEK|MOCK)$") String parserMode,
    @NotBlank String parserVersion,
    @NotBlank String ocrEngine) {

  public record ExtractedBooking(
      @NotNull BookingType type,
      String provider,
      String origin,
      String destination,
      String hotelName,
      String hotelAddress,
      String transportNumber,
      String confirmationNumber,
      Instant startTime,
      Instant endTime,
      @Positive Integer travelerCount,
      @Digits(integer = 15, fraction = 4) BigDecimal amount,
      @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
      @NotNull List<@NotBlank String> missingFields,
      @NotNull @Size(max = 100) List<@Valid FieldEvidence> fieldEvidence) {}

  public record FieldEvidence(
      @NotBlank String field,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
      String textEvidence,
      @Valid EvidenceRegion region) {}

  public record EvidenceRegion(
      @Positive int page,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal x,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal y,
      @NotNull @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") BigDecimal width,
      @NotNull @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") BigDecimal height) {}
}
