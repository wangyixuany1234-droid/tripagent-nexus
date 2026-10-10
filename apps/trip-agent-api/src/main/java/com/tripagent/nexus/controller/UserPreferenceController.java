package com.tripagent.nexus.controller;

import com.tripagent.nexus.application.idempotency.IdempotencyService;
import com.tripagent.nexus.application.security.ResourceAuthorizationService;
import com.tripagent.nexus.application.service.UserPreferenceService;
import com.tripagent.nexus.controller.dto.ApiModels.SaveUserPreferenceRequest;
import com.tripagent.nexus.controller.dto.ApiModels.UserPreferenceList;
import com.tripagent.nexus.domain.model.Phase14Models.UserPreference;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/{userId}/preferences")
public class UserPreferenceController {

  private final UserPreferenceService service;
  private final IdempotencyService idempotencyService;
  private final ResourceAuthorizationService authorization;

  public UserPreferenceController(
      UserPreferenceService service,
      IdempotencyService idempotencyService,
      ResourceAuthorizationService authorization) {
    this.service = service;
    this.idempotencyService = idempotencyService;
    this.authorization = authorization;
  }

  @GetMapping
  public UserPreferenceList list(@PathVariable UUID userId, Authentication authentication) {
    authorization.requireSameUser(authentication, userId);
    return new UserPreferenceList(service.list(userId));
  }

  @PostMapping
  public ResponseEntity<UserPreference> create(
      @PathVariable UUID userId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      Authentication authentication,
      @Valid @RequestBody SaveUserPreferenceRequest request) {
    authorization.requireSameUser(authentication, userId);
    UserPreference preference =
        idempotencyService.execute(
            idempotencyKey,
            "POST:/api/v1/users/{userId}/preferences:" + userId,
            request,
            UserPreference.class,
            () -> service.create(userId, request));
    return ResponseEntity.status(HttpStatus.CREATED).body(preference);
  }

  @PutMapping("/{preferenceId}")
  public UserPreference update(
      @PathVariable UUID userId,
      @PathVariable UUID preferenceId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      Authentication authentication,
      @Valid @RequestBody SaveUserPreferenceRequest request) {
    authorization.requireSameUser(authentication, userId);
    return idempotencyService.execute(
        idempotencyKey,
        "PUT:/api/v1/users/{userId}/preferences/{preferenceId}:" + preferenceId,
        request,
        UserPreference.class,
        () -> service.update(userId, preferenceId, request));
  }

  @PostMapping("/{preferenceId}/reject")
  public UserPreference reject(
      @PathVariable UUID userId,
      @PathVariable UUID preferenceId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      Authentication authentication) {
    authorization.requireSameUser(authentication, userId);
    return idempotencyService.execute(
        idempotencyKey,
        "POST:/api/v1/users/{userId}/preferences/{preferenceId}/reject:" + preferenceId,
        new PreferenceFingerprint(userId, preferenceId),
        UserPreference.class,
        () -> service.reject(userId, preferenceId));
  }

  @DeleteMapping("/{preferenceId}")
  public ResponseEntity<Void> delete(
      @PathVariable UUID userId,
      @PathVariable UUID preferenceId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      Authentication authentication) {
    authorization.requireSameUser(authentication, userId);
    idempotencyService.execute(
        idempotencyKey,
        "DELETE:/api/v1/users/{userId}/preferences/{preferenceId}:" + preferenceId,
        new PreferenceFingerprint(userId, preferenceId),
        UserPreference.class,
        () -> service.delete(userId, preferenceId));
    return ResponseEntity.noContent().build();
  }

  private record PreferenceFingerprint(UUID userId, UUID preferenceId) {}
}
