package com.tripagent.nexus.guardrail;

import com.tripagent.nexus.infrastructure.config.AiServiceProperties;
import com.tripagent.nexus.infrastructure.config.GuardrailProperties;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ExternalDomainGuard {

  private final Set<String> allowedHosts;
  private final AiServiceProperties aiServiceProperties;

  public ExternalDomainGuard(
      GuardrailProperties properties, AiServiceProperties aiServiceProperties) {
    this.allowedHosts =
        properties.getAllowedExternalHosts().stream()
            .map(host -> host.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    this.aiServiceProperties = aiServiceProperties;
  }

  @PostConstruct
  void validateConfiguredServices() {
    requireAllowed(aiServiceProperties.getBaseUrl());
  }

  public void requireAllowed(URI uri) {
    if (uri == null || uri.getHost() == null) {
      throw new IllegalStateException("EXTERNAL_TOOL_URI_INVALID");
    }
    String scheme = uri.getScheme();
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || !host.contains(".");
    if (!allowedHosts.contains(host)
        || (!"https".equalsIgnoreCase(scheme) && !(local && "http".equalsIgnoreCase(scheme)))) {
      throw new IllegalStateException("EXTERNAL_TOOL_DOMAIN_DENIED");
    }
  }
}
