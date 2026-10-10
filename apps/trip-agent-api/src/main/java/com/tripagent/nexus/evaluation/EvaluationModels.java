package com.tripagent.nexus.evaluation;

import com.tripagent.nexus.domain.model.DomainEnums.EvaluationRunStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EvaluationModels {

  private EvaluationModels() {}

  public record EvaluationMetrics(
      BigDecimal itineraryExecutabilityRate,
      BigDecimal constraintConflictRate,
      BigDecimal evidenceCoverageRate,
      BigDecimal toolCallSuccessRate,
      BigDecimal structuredOutputValidityRate,
      BigDecimal replanSuccessRate,
      long endToEndLatencyMs,
      long tokenUsage) {}

  public record EvaluationCaseResult(
      String caseId,
      String category,
      boolean passed,
      long latencyMs,
      long tokenUsage,
      String failureCode,
      List<String> observedCodes) {

    public EvaluationCaseResult {
      observedCodes = List.copyOf(observedCodes);
    }
  }

  public record EvaluationEnvironment(
      String applicationVersion,
      String javaVersion,
      String os,
      String modelProvider,
      String modelName,
      boolean mockMode) {}

  public record EvaluationRun(
      UUID runId,
      UUID taskId,
      String datasetVersion,
      EvaluationRunStatus status,
      int sampleSize,
      EvaluationMetrics metrics,
      List<EvaluationCaseResult> cases,
      EvaluationEnvironment environment,
      String traceId,
      Instant startedAt,
      Instant completedAt,
      Instant createdAt,
      Instant updatedAt) {

    public EvaluationRun {
      cases = List.copyOf(cases);
    }
  }

  public record EvaluationDataset(String version, List<EvaluationCase> cases) {

    public EvaluationDataset {
      cases = List.copyOf(cases);
    }
  }

  public record EvaluationCase(
      String caseId, String category, String scenario, List<String> expectedCodes) {

    public EvaluationCase {
      expectedCodes = List.copyOf(expectedCodes);
    }
  }

  public record ReplayObservation(
      boolean itineraryExecutable,
      int detectedConflicts,
      int expectedConflicts,
      int evidencedRecommendations,
      int totalRecommendations,
      int successfulToolCalls,
      int totalToolCalls,
      int validStructuredOutputs,
      int totalStructuredOutputs,
      int successfulReplans,
      int totalReplans,
      long tokenUsage,
      List<String> observedCodes) {

    public ReplayObservation {
      observedCodes = List.copyOf(observedCodes);
    }
  }
}
