package com.tripagent.nexus.infrastructure.persistence.entity;

import com.tripagent.nexus.domain.model.Phase14Models.PreferenceSource;
import com.tripagent.nexus.domain.model.Phase14Models.PreferenceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_preference")
public class UserPreferenceEntity {

  @Id
  @Column(name = "preference_id", nullable = false)
  private UUID preferenceId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(nullable = false, length = 100)
  private String category;

  @Column(nullable = false, length = 500)
  private String value;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private PreferenceSource source;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private PreferenceStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "entity_version", nullable = false)
  private long entityVersion;

  protected UserPreferenceEntity() {}

  public UserPreferenceEntity(
      UUID preferenceId,
      UUID userId,
      String category,
      String value,
      PreferenceSource source,
      Instant now) {
    this.preferenceId = preferenceId;
    this.userId = userId;
    this.category = category;
    this.value = value;
    this.source = source;
    this.status = PreferenceStatus.ACTIVE;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void update(String category, String value, Instant now) {
    this.category = category;
    this.value = value;
    this.source = PreferenceSource.USER;
    this.status = PreferenceStatus.ACTIVE;
    this.updatedAt = now;
  }

  public void reject(Instant now) {
    this.status = PreferenceStatus.REJECTED;
    this.updatedAt = now;
  }

  public UUID getPreferenceId() {
    return preferenceId;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getCategory() {
    return category;
  }

  public String getValue() {
    return value;
  }

  public PreferenceSource getSource() {
    return source;
  }

  public PreferenceStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
