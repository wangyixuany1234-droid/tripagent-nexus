package com.tripagent.nexus.controller;

import com.tripagent.nexus.application.security.AuthenticatedActorService;
import com.tripagent.nexus.application.service.DeveloperTraceService;
import com.tripagent.nexus.domain.model.Phase14Models.DeveloperTrace;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/developer/traces")
public class DeveloperTraceController {

  private final DeveloperTraceService service;
  private final AuthenticatedActorService actors;

  public DeveloperTraceController(DeveloperTraceService service, AuthenticatedActorService actors) {
    this.service = service;
    this.actors = actors;
  }

  @GetMapping("/{traceId}")
  public DeveloperTrace get(
      @PathVariable @Pattern(regexp = "^[0-9a-f]{32}$") String traceId,
      @RequestHeader(value = "X-Actor-Role", required = false) String testActorRole,
      Authentication authentication) {
    service.authorize(actors.isAdmin(authentication, testActorRole) ? "ADMIN" : null);
    return service.get(traceId);
  }
}
