package com.tripagent.nexus.application.service;

import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.infrastructure.config.PlanningProperties;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class UserMemoryCleanupService {

  private final RestClient restClient;
  private final PlanningProperties planningProperties;

  public UserMemoryCleanupService(
      @Qualifier("aiRetrievalRestClient") RestClient restClient,
      PlanningProperties planningProperties) {
    this.restClient = restClient;
    this.planningProperties = planningProperties;
  }

  public void delete(UUID userId, UUID preferenceId) {
    try {
      restClient
          .delete()
          .uri("/internal/v1/memory/{preferenceId}", preferenceId)
          .header("Idempotency-Key", "memory-delete-" + preferenceId)
          .header("X-Tenant-Id", planningProperties.getTenantId().toString())
          .header("X-User-Id", userId.toString())
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException exception) {
      throw new ApiException(
          "USER_MEMORY_CLEANUP_UNAVAILABLE",
          HttpStatus.SERVICE_UNAVAILABLE,
          "User memory cleanup could not be completed.");
    }
  }
}
