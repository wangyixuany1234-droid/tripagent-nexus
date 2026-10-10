package com.tripagent.nexus.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

public final class AgentPromptLoader {

  private AgentPromptLoader() {}

  public static String load(String version) {
    ClassPathResource resource = new ClassPathResource("prompts/agents/" + version + ".txt");
    try {
      return resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Agent prompt is missing: " + version, exception);
    }
  }
}
