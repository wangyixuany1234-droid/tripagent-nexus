package com.tripagent.nexus.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class AgentContracts {

  private AgentContracts() {}

  public interface AgentInput {
    UUID taskId();

    ContextEnvelope context();
  }

  public record AgentEvidence(
      @NotBlank String sourceId,
      @NotBlank String sourceType,
      @NotBlank String title,
      @NotBlank @Size(max = 3000) String excerpt,
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal relevanceScore,
      boolean fromRealtimeTool) {
    public AgentEvidence {
      relevanceScore = relevanceScore == null ? BigDecimal.ZERO : relevanceScore;
    }

    public AgentEvidence summarized(int maxChars) {
      if (excerpt == null || excerpt.length() <= maxChars) {
        return this;
      }
      return new AgentEvidence(
          sourceId,
          sourceType,
          title,
          excerpt.substring(0, Math.max(0, maxChars - 3)) + "...",
          relevanceScore,
          fromRealtimeTool);
    }
  }

  public record AgentWarning(@NotBlank String code, @NotBlank @Size(max = 1000) String message) {}

  public record RetryPolicy(
      @Positive int maxAttempts,
      @NotNull java.time.Duration initialBackoff,
      @NotNull java.time.Duration maximumBackoff) {}

  public record AgentPolicy(
      @NotNull java.time.Duration timeout,
      @NotNull @Valid RetryPolicy retryPolicy,
      @NotNull Set<@NotBlank String> errorCodes,
      boolean allowDegraded,
      @NotBlank String promptTemplateVersion) {
    public AgentPolicy {
      errorCodes = Set.copyOf(errorCodes);
    }
  }

  public record AgentResultEnvelope<T>(
      @NotNull UUID taskId,
      @NotNull AgentName agentName,
      @NotNull AgentStatus status,
      @NotNull @Valid T result,
      @NotNull List<@Valid AgentEvidence> evidence,
      @NotNull List<@Valid AgentWarning> warnings,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{32}$") String traceId) {
    public AgentResultEnvelope {
      evidence = List.copyOf(evidence);
      warnings = List.copyOf(warnings);
    }
  }

  public record UserConstraint(
      @NotBlank String constraintId,
      @NotNull ConstraintKind kind,
      @NotBlank @Size(max = 1000) String statement,
      boolean protectedFromSummarization) {}

  public enum ConstraintKind {
    HARD,
    SOFT
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record BookingContext(
      @NotNull UUID bookingId,
      @NotBlank String bookingType,
      @JsonProperty(required = false) Instant startTime,
      @JsonProperty(required = false) Instant endTime,
      @JsonProperty(required = false) @DecimalMin("0.0") BigDecimal amount,
      @JsonProperty(required = false) @Pattern(regexp = "^[A-Z]{3}$") String currency,
      boolean confirmed,
      @NotBlank @Size(max = 1000) String summary) {}

  public record ToolResultContext(
      @NotBlank String toolName, @NotBlank String status, @NotNull JsonNode result) {}

  public record AgentResultContext(
      @NotNull UUID taskId,
      @NotNull AgentName agentName,
      @NotNull AgentStatus status,
      @NotNull JsonNode result) {}

  public record PrivacyLabel(
      @NotBlank String jsonPointer,
      @NotNull PrivacyClassification classification,
      @NotNull Set<AgentName> allowedAgents) {
    public PrivacyLabel {
      allowedAgents =
          java.util.Collections.unmodifiableSet(
              allowedAgents.isEmpty()
                  ? java.util.EnumSet.noneOf(AgentName.class)
                  : java.util.EnumSet.copyOf(allowedAgents));
    }
  }

  public enum PrivacyClassification {
    PUBLIC,
    INTERNAL,
    PERSONAL,
    SENSITIVE
  }
}
