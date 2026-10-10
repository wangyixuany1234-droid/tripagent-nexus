package com.tripagent.nexus.infrastructure.persistence.entity;

import com.tripagent.nexus.domain.model.DomainEnums.TripStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trip")
public class TripEntity {

  @Id
  @Column(name = "trip_id", nullable = false)
  private UUID tripId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(nullable = false, length = 160)
  private String title;

  @Column(name = "time_zone", nullable = false, length = 64)
  private String timeZone;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private TripStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected TripEntity() {}

  public TripEntity(UUID tripId, UUID userId, String title, String timeZone, Instant now) {
    this.tripId = tripId;
    this.userId = userId;
    this.title = title;
    this.timeZone = timeZone;
    this.status = TripStatus.DRAFT;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void markReady(Instant now) {
    this.status = TripStatus.READY;
    this.updatedAt = now;
  }

  public UUID getTripId() {
    return tripId;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getTitle() {
    return title;
  }

  public String getTimeZone() {
    return timeZone;
  }

  public TripStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
