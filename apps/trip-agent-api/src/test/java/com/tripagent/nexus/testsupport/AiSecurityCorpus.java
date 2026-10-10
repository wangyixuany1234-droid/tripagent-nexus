package com.tripagent.nexus.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.params.provider.Arguments;

public final class AiSecurityCorpus {
  private AiSecurityCorpus() {}

  public static Stream<Arguments> cases(String group) {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.exists(root.resolve("qa/security/ai-stage9-corpus.json"))) {
      root = root.getParent();
    }
    if (root == null) throw new IllegalStateException("AI security corpus missing");
    try {
      JsonNode entries =
          new ObjectMapper()
              .readTree(root.resolve("qa/security/ai-stage9-corpus.json").toFile())
              .path(group);
      return StreamSupport.stream(entries.spliterator(), false)
          .map(item -> Arguments.of(item.path("id").asText(), item.path("text").asText()));
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("AI security corpus invalid", exception);
    }
  }
}
