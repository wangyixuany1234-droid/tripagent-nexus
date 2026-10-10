package com.tripagent.nexus.infrastructure.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripagent.nexus.domain.model.DomainEnums.BookingReviewStatus;
import com.tripagent.nexus.domain.model.DomainEnums.BookingType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "parsed_booking")
public class ParsedBookingEntity {

  @Id
  @Column(name = "booking_id", nullable = false)
  private UUID bookingId;

  @Enumerated(EnumType.STRING)
  @Column(name = "booking_type", nullable = false, length = 32)
  private BookingType type;

  @Column(name = "source_document_id", nullable = false)
  private UUID sourceDocumentId;

  @Column(length = 200)
  private String provider;

  @Column(length = 300)
  private String origin;

  @Column(length = 300)
  private String destination;

  @Column(name = "hotel_name", length = 300)
  private String hotelName;

  @Column(name = "hotel_address", length = 500)
  private String hotelAddress;

  @Column(name = "transport_number", length = 100)
  private String transportNumber;

  @Column(name = "confirmation_number", length = 200)
  private String confirmationNumber;

  @Column(name = "start_time")
  private Instant startTime;

  @Column(name = "end_time")
  private Instant endTime;

  @Column(name = "traveler_count")
  private Integer travelerCount;

  @Column(precision = 19, scale = 4)
  private BigDecimal amount;

  @Column(length = 3)
  private String currency;

  @Column(nullable = false, precision = 5, scale = 4)
  private BigDecimal confidence;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "missing_fields", nullable = false, columnDefinition = "jsonb")
  private JsonNode missingFields;

  @Column(name = "raw_evidence", nullable = false)
  private String rawEvidence;

  @Enumerated(EnumType.STRING)
  @Column(name = "review_status", nullable = false, length = 32)
  private BookingReviewStatus reviewStatus;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "field_evidence", nullable = false, columnDefinition = "jsonb")
  private JsonNode fieldEvidence;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "validation_issues", nullable = false, columnDefinition = "jsonb")
  private JsonNode validationIssues;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "entity_version", nullable = false)
  private long entityVersion;

  protected ParsedBookingEntity() {}

  public ParsedBookingEntity(
      UUID bookingId,
      BookingType type,
      UUID sourceDocumentId,
      String provider,
      String origin,
      String destination,
      String hotelName,
      String hotelAddress,
      String transportNumber,
      String confirmationNumber,
      Instant startTime,
      Instant endTime,
      Integer travelerCount,
      BigDecimal amount,
      String currency,
      BigDecimal confidence,
      JsonNode missingFields,
      BookingReviewStatus reviewStatus,
      JsonNode fieldEvidence,
      JsonNode validationIssues,
      Instant now) {
    this.bookingId = bookingId;
    this.type = type;
    this.sourceDocumentId = sourceDocumentId;
    this.provider = provider;
    this.origin = origin;
    this.destination = destination;
    this.hotelName = hotelName;
    this.hotelAddress = hotelAddress;
    this.transportNumber = transportNumber;
    this.confirmationNumber = confirmationNumber;
    this.startTime = startTime;
    this.endTime = endTime;
    this.travelerCount = travelerCount;
    this.amount = amount;
    this.currency = currency;
    this.confidence = confidence;
    this.missingFields = missingFields;
    this.rawEvidence = "";
    this.reviewStatus = reviewStatus;
    this.fieldEvidence = fieldEvidence;
    this.validationIssues = validationIssues;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getBookingId() {
    return bookingId;
  }

  public BookingType getType() {
    return type;
  }

  public void setType(BookingType type) {
    this.type = type;
  }

  public UUID getSourceDocumentId() {
    return sourceDocumentId;
  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getOrigin() {
    return origin;
  }

  public void setOrigin(String origin) {
    this.origin = origin;
  }

  public String getDestination() {
    return destination;
  }

  public void setDestination(String destination) {
    this.destination = destination;
  }

  public String getHotelName() {
    return hotelName;
  }

  public void setHotelName(String hotelName) {
    this.hotelName = hotelName;
  }

  public String getHotelAddress() {
    return hotelAddress;
  }

  public void setHotelAddress(String hotelAddress) {
    this.hotelAddress = hotelAddress;
  }

  public String getTransportNumber() {
    return transportNumber;
  }

  public void setTransportNumber(String transportNumber) {
    this.transportNumber = transportNumber;
  }

  public String getConfirmationNumber() {
    return confirmationNumber;
  }

  public void setConfirmationNumber(String confirmationNumber) {
    this.confirmationNumber = confirmationNumber;
  }

  public Instant getStartTime() {
    return startTime;
  }

  public void setStartTime(Instant startTime) {
    this.startTime = startTime;
  }

  public Instant getEndTime() {
    return endTime;
  }

  public void setEndTime(Instant endTime) {
    this.endTime = endTime;
  }

  public Integer getTravelerCount() {
    return travelerCount;
  }

  public void setTravelerCount(Integer travelerCount) {
    this.travelerCount = travelerCount;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public BigDecimal getConfidence() {
    return confidence;
  }

  public JsonNode getMissingFields() {
    return missingFields;
  }

  public void setMissingFields(JsonNode missingFields) {
    this.missingFields = missingFields;
  }

  public BookingReviewStatus getReviewStatus() {
    return reviewStatus;
  }

  public void setReviewStatus(BookingReviewStatus reviewStatus) {
    this.reviewStatus = reviewStatus;
  }

  public JsonNode getFieldEvidence() {
    return fieldEvidence;
  }

  public void setFieldEvidence(JsonNode fieldEvidence) {
    this.fieldEvidence = fieldEvidence;
  }

  public JsonNode getValidationIssues() {
    return validationIssues;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setValidationIssues(JsonNode validationIssues) {
    this.validationIssues = validationIssues;
  }

  public void touch(Instant now) {
    this.updatedAt = now;
  }
}
