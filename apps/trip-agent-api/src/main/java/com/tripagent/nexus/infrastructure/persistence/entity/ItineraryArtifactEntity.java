package com.tripagent.nexus.infrastructure.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "itinerary_artifact")
public class ItineraryArtifactEntity {

  @Id
  @Column(name = "itinerary_artifact_id", nullable = false)
  private UUID itineraryArtifactId;

  @Column(name = "trip_id", nullable = false)
  private UUID tripId;

  @Column(name = "plan_version", nullable = false)
  private int planVersion;

  @Column(name = "idempotency_key", nullable = false, length = 128, unique = true)
  private String idempotencyKey;

  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode itinerary;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ItineraryArtifactEntity() {}

  public ItineraryArtifactEntity(
      UUID itineraryArtifactId,
      UUID tripId,
      int planVersion,
      String idempotencyKey,
      String requestHash,
      JsonNode itinerary,
      Instant createdAt) {
    this.itineraryArtifactId = itineraryArtifactId;
    this.tripId = tripId;
    this.planVersion = planVersion;
    this.idempotencyKey = idempotencyKey;
    this.requestHash = requestHash;
    this.itinerary = itinerary;
    this.createdAt = createdAt;
  }

  public UUID getItineraryArtifactId() {
    return itineraryArtifactId;
  }

  public UUID getTripId() {
    return tripId;
  }

  public int getPlanVersion() {
    return planVersion;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public JsonNode getItinerary() {
    return itinerary;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
