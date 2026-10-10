package com.tripagent.nexus.application.service;

import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.controller.dto.ApiModels.SaveUserPreferenceRequest;
import com.tripagent.nexus.domain.model.Phase14Models.PreferenceSource;
import com.tripagent.nexus.domain.model.Phase14Models.UserPreference;
import com.tripagent.nexus.guardrail.PromptInjectionGuard;
import com.tripagent.nexus.infrastructure.persistence.entity.UserPreferenceEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.UserPreferenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserPreferenceService {

  private final UserPreferenceRepository repository;
  private final UserMemoryCleanupService cleanupService;
  private final PromptInjectionGuard promptInjectionGuard;
  private final Clock clock;

  public UserPreferenceService(
      UserPreferenceRepository repository,
      UserMemoryCleanupService cleanupService,
      PromptInjectionGuard promptInjectionGuard,
      Clock clock) {
    this.repository = repository;
    this.cleanupService = cleanupService;
    this.promptInjectionGuard = promptInjectionGuard;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<UserPreference> list(UUID userId) {
    return repository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
        .map(UserPreferenceService::toDomain)
        .toList();
  }

  @Transactional
  public UserPreference create(UUID userId, SaveUserPreferenceRequest request) {
    validate(request);
    Instant now = clock.instant();
    return toDomain(
        repository.save(
            new UserPreferenceEntity(
                UUID.randomUUID(),
                userId,
                request.category().trim(),
                request.value().trim(),
                PreferenceSource.USER,
                now)));
  }

  @Transactional
  public UserPreference createInferred(UUID userId, String category, String value) {
    promptInjectionGuard.requireSafe(value);
    Instant now = clock.instant();
    return toDomain(
        repository.save(
            new UserPreferenceEntity(
                UUID.randomUUID(),
                userId,
                category.trim(),
                value.trim(),
                PreferenceSource.INFERRED,
                now)));
  }

  @Transactional
  public UserPreference update(UUID userId, UUID preferenceId, SaveUserPreferenceRequest request) {
    validate(request);
    UserPreferenceEntity entity = requireOwned(userId, preferenceId);
    entity.update(request.category().trim(), request.value().trim(), clock.instant());
    return toDomain(repository.save(entity));
  }

  @Transactional
  public UserPreference reject(UUID userId, UUID preferenceId) {
    UserPreferenceEntity entity = requireOwned(userId, preferenceId);
    if (entity.getSource() != PreferenceSource.INFERRED) {
      throw new ApiException(
          "PREFERENCE_NOT_INFERRED",
          HttpStatus.CONFLICT,
          "Only an inferred preference can be rejected.");
    }
    cleanupService.delete(userId, preferenceId);
    entity.reject(clock.instant());
    return toDomain(repository.save(entity));
  }

  @Transactional
  public UserPreference delete(UUID userId, UUID preferenceId) {
    UserPreferenceEntity entity = requireOwned(userId, preferenceId);
    cleanupService.delete(userId, preferenceId);
    UserPreference deleted = toDomain(entity);
    repository.delete(entity);
    return deleted;
  }

  private void validate(SaveUserPreferenceRequest request) {
    promptInjectionGuard.requireSafe(request.category());
    promptInjectionGuard.requireSafe(request.value());
  }

  private UserPreferenceEntity requireOwned(UUID userId, UUID preferenceId) {
    return repository
        .findByPreferenceIdAndUserId(preferenceId, userId)
        .orElseThrow(
            () ->
                new ApiException(
                    "PREFERENCE_NOT_FOUND",
                    HttpStatus.NOT_FOUND,
                    "User preference was not found."));
  }

  private static UserPreference toDomain(UserPreferenceEntity entity) {
    return new UserPreference(
        entity.getPreferenceId(),
        entity.getUserId(),
        entity.getCategory(),
        entity.getValue(),
        entity.getSource(),
        entity.getStatus(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
