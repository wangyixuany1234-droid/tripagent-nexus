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
@Table(name = "feedback")
public class FeedbackEntity {

  @Id
  @Column(name = "feedback_id", nullable = false)
  private UUID feedbackId;

  @Column(name = "trip_id", nullable = false)
  private UUID tripId;

  @Column(name = "plan_id")
  private UUID planId;

  @Column(name = "target_type", length = 32)
  private String targetType;

  @Column(name = "target_id", length = 200)
  private String targetId;

  @Column(length = 32)
  private String sentiment;

  @Column(nullable = false)
  private int rating;

  @Column(nullable = false, length = 5000)
  private String comment;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode tags;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "memory_accepted", nullable = false)
  private boolean memoryAccepted;

  protected FeedbackEntity() {}

  public FeedbackEntity(
      UUID feedbackId,
      UUID tripId,
      UUID planId,
      String targetType,
      String targetId,
      String sentiment,
      int rating,
      String comment,
      JsonNode tags,
      boolean memoryAccepted,
      Instant createdAt) {
    this.feedbackId = feedbackId;
    this.tripId = tripId;
    this.planId = planId;
    this.targetType = targetType;
    this.targetId = targetId;
    this.sentiment = sentiment;
    this.rating = rating;
    this.comment = comment;
    this.tags = tags;
    this.memoryAccepted = memoryAccepted;
    this.createdAt = createdAt;
  }

  public UUID getFeedbackId() {
    return feedbackId;
  }

  public UUID getTripId() {
    return tripId;
  }

  public UUID getPlanId() {
    return planId;
  }

  public String getTargetType() {
    return targetType;
  }

  public String getTargetId() {
    return targetId;
  }

  public String getSentiment() {
    return sentiment;
  }

  public int getRating() {
    return rating;
  }

  public String getComment() {
    return comment;
  }

  public JsonNode getTags() {
    return tags;
  }

  public boolean isMemoryAccepted() {
    return memoryAccepted;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
