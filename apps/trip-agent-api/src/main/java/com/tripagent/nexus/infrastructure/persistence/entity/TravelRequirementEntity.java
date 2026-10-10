package com.tripagent.nexus.infrastructure.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripagent.nexus.domain.model.DomainEnums.Pace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "travel_requirement")
public class TravelRequirementEntity {

  @Id
  @Column(name = "trip_id", nullable = false)
  private UUID tripId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode origin;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode destinations;

  @Column(name = "start_date", nullable = false)
  private LocalDate startDate;

  @Column(name = "end_date", nullable = false)
  private LocalDate endDate;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode travelers;

  @Column(name = "total_budget", nullable = false, precision = 19, scale = 4)
  private BigDecimal totalBudget;

  @Column(nullable = false, length = 3)
  private String currency;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode interests;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private Pace pace;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "must_visit", nullable = false, columnDefinition = "jsonb")
  private JsonNode mustVisit;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode avoid;

  @Column(name = "free_text_constraints", nullable = false, length = 5000)
  private String freeTextConstraints;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected TravelRequirementEntity() {}

  public TravelRequirementEntity(
      UUID tripId,
      JsonNode origin,
      JsonNode destinations,
      LocalDate startDate,
      LocalDate endDate,
      JsonNode travelers,
      BigDecimal totalBudget,
      String currency,
      JsonNode interests,
      Pace pace,
      JsonNode mustVisit,
      JsonNode avoid,
      String freeTextConstraints,
      Instant updatedAt) {
    this.tripId = tripId;
    update(
        origin,
        destinations,
        startDate,
        endDate,
        travelers,
        totalBudget,
        currency,
        interests,
        pace,
        mustVisit,
        avoid,
        freeTextConstraints,
        updatedAt);
  }

  public void update(
      JsonNode origin,
      JsonNode destinations,
      LocalDate startDate,
      LocalDate endDate,
      JsonNode travelers,
      BigDecimal totalBudget,
      String currency,
      JsonNode interests,
      Pace pace,
      JsonNode mustVisit,
      JsonNode avoid,
      String freeTextConstraints,
      Instant updatedAt) {
    this.origin = origin;
    this.destinations = destinations;
    this.startDate = startDate;
    this.endDate = endDate;
    this.travelers = travelers;
    this.totalBudget = totalBudget;
    this.currency = currency;
    this.interests = interests;
    this.pace = pace;
    this.mustVisit = mustVisit;
    this.avoid = avoid;
    this.freeTextConstraints = freeTextConstraints;
    this.updatedAt = updatedAt;
  }

  public UUID getTripId() {
    return tripId;
  }

  public JsonNode getOrigin() {
    return origin;
  }

  public JsonNode getDestinations() {
    return destinations;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public LocalDate getEndDate() {
    return endDate;
  }

  public JsonNode getTravelers() {
    return travelers;
  }

  public BigDecimal getTotalBudget() {
    return totalBudget;
  }

  public String getCurrency() {
    return currency;
  }

  public JsonNode getInterests() {
    return interests;
  }

  public Pace getPace() {
    return pace;
  }

  public JsonNode getMustVisit() {
    return mustVisit;
  }

  public JsonNode getAvoid() {
    return avoid;
  }

  public String getFreeTextConstraints() {
    return freeTextConstraints;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
