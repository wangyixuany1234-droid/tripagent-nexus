package com.tripagent.nexus.skill;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record McpToolResult(
    Status status, ProviderMode providerMode, JsonNode data, List<String> warnings) {

  public McpToolResult {
    warnings = List.copyOf(warnings);
  }

  public enum Status {
    SUCCEEDED,
    UNAVAILABLE,
    FAILED
  }

  public enum ProviderMode {
    LIVE,
    MOCK,
    INTERNAL
  }
}
