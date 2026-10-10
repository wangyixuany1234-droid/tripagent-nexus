package com.tripagent.nexus.guardrail;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tripagent.nexus.infrastructure.config.AiServiceProperties;
import com.tripagent.nexus.infrastructure.config.GuardrailProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalDomainGuardTest {

  @Test
  void allowsConfiguredHttpsAndLocalHttpOnly() {
    GuardrailProperties properties = new GuardrailProperties();
    properties.setAllowedExternalHosts(
        List.of("api.example.com", "localhost", "ai-retrieval-service"));
    ExternalDomainGuard guard = new ExternalDomainGuard(properties, new AiServiceProperties());

    assertThatCode(() -> guard.requireAllowed(URI.create("https://api.example.com/tool")))
        .doesNotThrowAnyException();
    assertThatCode(() -> guard.requireAllowed(URI.create("http://localhost:8080/mcp")))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> guard.requireAllowed(URI.create("http://ai-retrieval-service:8000/internal")))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> guard.requireAllowed(URI.create("https://untrusted.example/tool")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("EXTERNAL_TOOL_DOMAIN_DENIED");
    assertThatThrownBy(() -> guard.requireAllowed(URI.create("http://api.example.com/tool")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("EXTERNAL_TOOL_DOMAIN_DENIED");
  }
}
