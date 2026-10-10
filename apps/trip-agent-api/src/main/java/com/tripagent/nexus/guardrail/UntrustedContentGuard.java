package com.tripagent.nexus.guardrail;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class UntrustedContentGuard {

  private static final int MAX_EXCERPT_LENGTH = 1000;
  private static final String UNTRUSTED_PREFIX = "[UNTRUSTED_RETRIEVAL_DATA] ";
  private final PromptInjectionGuard promptInjectionGuard;

  public UntrustedContentGuard(PromptInjectionGuard promptInjectionGuard) {
    this.promptInjectionGuard = promptInjectionGuard;
  }

  public Optional<String> isolate(String content) {
    if (content == null || content.isBlank()) {
      return Optional.empty();
    }
    PromptInjectionGuard.Decision decision = promptInjectionGuard.inspect(content);
    if (!decision.allowed()) {
      promptInjectionGuard.recordRejection(decision, "retrieval_content");
      return Optional.empty();
    }
    String normalized =
        content.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").replaceAll("\\s+", " ").trim();
    if (normalized.startsWith(UNTRUSTED_PREFIX)) {
      normalized = normalized.substring(UNTRUSTED_PREFIX.length());
    }
    int contentLimit = MAX_EXCERPT_LENGTH - UNTRUSTED_PREFIX.length();
    if (normalized.length() > contentLimit) {
      normalized = normalized.substring(0, contentLimit);
    }
    return Optional.of(UNTRUSTED_PREFIX + normalized);
  }
}
