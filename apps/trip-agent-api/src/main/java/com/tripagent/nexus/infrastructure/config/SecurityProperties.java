package com.tripagent.nexus.infrastructure.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

  private boolean enabled = true;

  @NotEmpty private List<String> allowedOrigins = new ArrayList<>();

  @NotBlank private String issuerUri = "https://auth.invalid.tripagent.local";

  @NotBlank private String audience = "tripagent-api";

  @NotBlank private String jwkSetUri = "http://127.0.0.1:65535/.well-known/jwks.json";

  @Min(1) @Max(1000) private int planSubmissionLimitPerMinute = 10;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public List<String> getAllowedOrigins() {
    return List.copyOf(allowedOrigins);
  }

  public void setAllowedOrigins(List<String> allowedOrigins) {
    this.allowedOrigins = new ArrayList<>(allowedOrigins);
  }

  public String getIssuerUri() {
    return issuerUri;
  }

  public void setIssuerUri(String issuerUri) {
    this.issuerUri = issuerUri;
  }

  public String getAudience() {
    return audience;
  }

  public void setAudience(String audience) {
    this.audience = audience;
  }

  public String getJwkSetUri() {
    return jwkSetUri;
  }

  public void setJwkSetUri(String jwkSetUri) {
    this.jwkSetUri = jwkSetUri;
  }

  public int getPlanSubmissionLimitPerMinute() {
    return planSubmissionLimitPerMinute;
  }

  public void setPlanSubmissionLimitPerMinute(int planSubmissionLimitPerMinute) {
    this.planSubmissionLimitPerMinute = planSubmissionLimitPerMinute;
  }

  @AssertTrue(message = "CORS origins must be absolute http(s) origins without wildcards")
  public boolean isAllowedOriginsSafe() {
    return allowedOrigins.stream().allMatch(SecurityProperties::isSafeOrigin);
  }

  @AssertTrue(message = "JWT issuer and JWK set must use HTTPS or loopback HTTP") public boolean isJwtConfigurationSafe() {
    return isTrustedIdentityUri(issuerUri) && isTrustedIdentityUri(jwkSetUri);
  }

  private static boolean isSafeOrigin(String origin) {
    if (origin == null || origin.contains("*")) {
      return false;
    }
    try {
      URI uri = URI.create(origin);
      return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          && uri.getHost() != null
          && uri.getPath().isEmpty()
          && uri.getQuery() == null
          && uri.getFragment() == null;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean isTrustedIdentityUri(String value) {
    try {
      URI uri = URI.create(value);
      if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
        return false;
      }
      if ("https".equals(uri.getScheme())) {
        return true;
      }
      return "http".equals(uri.getScheme())
          && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}
