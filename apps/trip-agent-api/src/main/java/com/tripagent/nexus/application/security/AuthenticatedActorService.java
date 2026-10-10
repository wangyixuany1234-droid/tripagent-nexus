package com.tripagent.nexus.application.security;

import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.infrastructure.config.SecurityProperties;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
public class AuthenticatedActorService {

  private final SecurityProperties properties;

  public AuthenticatedActorService(SecurityProperties properties) {
    this.properties = properties;
  }

  public UUID requireUserId(Authentication authentication) {
    JwtAuthenticationToken token = requireToken(authentication);
    try {
      return UUID.fromString(token.getToken().getSubject());
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw unauthenticated();
    }
  }

  public String actorId(Authentication authentication, String testFallback) {
    if (!properties.isEnabled()) {
      return testFallback;
    }
    return requireUserId(authentication).toString();
  }

  public boolean isAdmin(Authentication authentication, String testFallbackRole) {
    if (!properties.isEnabled()) {
      return "ADMIN".equals(testFallbackRole);
    }
    JwtAuthenticationToken token = requireToken(authentication);
    return token.getAuthorities().stream()
        .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
  }

  public boolean isEnforced() {
    return properties.isEnabled();
  }

  private static JwtAuthenticationToken requireToken(Authentication authentication) {
    if (!(authentication instanceof JwtAuthenticationToken token)
        || !authentication.isAuthenticated()) {
      throw unauthenticated();
    }
    return token;
  }

  private static ApiException unauthenticated() {
    return new ApiException(
        "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED, "A valid user session is required.");
  }
}
