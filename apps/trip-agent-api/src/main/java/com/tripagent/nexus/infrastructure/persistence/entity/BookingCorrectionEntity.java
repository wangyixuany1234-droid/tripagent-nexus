package com.tripagent.nexus.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_correction_audit")
public class BookingCorrectionEntity {

  @Id
  @Column(name = "audit_id", nullable = false)
  private UUID auditId;

  @Column(name = "booking_id", nullable = false)
  private UUID bookingId;

  @Column(name = "field_name", nullable = false, length = 100)
  private String field;

  @Column(name = "original_value", nullable = false, length = 2000)
  private String originalValue;

  @Column(name = "corrected_value", nullable = false, length = 2000)
  private String correctedValue;

  @Column(name = "actor_id", nullable = false, length = 128)
  private String actorId;

  @Column(length = 500)
  private String reason;

  @Column(name = "corrected_at", nullable = false)
  private Instant correctedAt;

  protected BookingCorrectionEntity() {}

  public BookingCorrectionEntity(
      UUID auditId,
      UUID bookingId,
      String field,
      String originalValue,
      String correctedValue,
      String actorId,
      String reason,
      Instant correctedAt) {
    this.auditId = auditId;
    this.bookingId = bookingId;
    this.field = field;
    this.originalValue = originalValue;
    this.correctedValue = correctedValue;
    this.actorId = actorId;
    this.reason = reason;
    this.correctedAt = correctedAt;
  }

  public UUID getAuditId() {
    return auditId;
  }

  public String getField() {
    return field;
  }

  public String getOriginalValue() {
    return originalValue;
  }

  public String getCorrectedValue() {
    return correctedValue;
  }

  public String getActorId() {
    return actorId;
  }

  public String getReason() {
    return reason;
  }

  public Instant getCorrectedAt() {
    return correctedAt;
  }
}
