package com.tripagent.nexus.evaluation;

import com.tripagent.nexus.evaluation.EvaluationModels.EvaluationCase;
import com.tripagent.nexus.evaluation.EvaluationModels.EvaluationCaseResult;
import com.tripagent.nexus.evaluation.EvaluationModels.EvaluationDataset;
import com.tripagent.nexus.evaluation.EvaluationModels.EvaluationMetrics;
import com.tripagent.nexus.evaluation.EvaluationModels.ReplayObservation;
import com.tripagent.nexus.observability.TraceOperations;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Component;

@Component
public class EvaluationHarness {

  private final EvaluationCaseReplayer replayer;
  private final TraceOperations traces;

  public EvaluationHarness(EvaluationCaseReplayer replayer, TraceOperations traces) {
    this.replayer = replayer;
    this.traces = traces;
  }

  public HarnessResult replay(
      EvaluationDataset dataset, BiConsumer<Integer, String> progressConsumer) {
    long started = System.nanoTime();
    List<EvaluationCaseResult> results = new ArrayList<>();
    List<CaseObservation> observations = new ArrayList<>();
    for (int index = 0; index < dataset.cases().size(); index++) {
      EvaluationCase evaluationCase = dataset.cases().get(index);
      int progress = 10 + ((index + 1) * 80 / dataset.cases().size());
      progressConsumer.accept(progress, evaluationCase.caseId());
      long caseStarted = System.nanoTime();
      try {
        ReplayObservation observation =
            traces.trace(
                "evaluation.case",
                "tripagent.evaluation.case",
                Map.of(
                    "evaluation.dataset.version", dataset.version(),
                    "evaluation.case.id", evaluationCase.caseId(),
                    "evaluation.category", evaluationCase.category()),
                () -> replayer.replay(evaluationCase));
        observations.add(new CaseObservation(evaluationCase, observation));
        List<String> observedCodes =
            observation.observedCodes().stream().distinct().sorted().toList();
        Set<String> expected = new LinkedHashSet<>(evaluationCase.expectedCodes());
        boolean passed = new LinkedHashSet<>(observedCodes).equals(expected);
        results.add(
            new EvaluationCaseResult(
                evaluationCase.caseId(),
                evaluationCase.category(),
                passed,
                elapsedMillis(caseStarted),
                observation.tokenUsage(),
                passed ? null : "EXPECTED_OUTCOME_MISMATCH",
                observedCodes));
      } catch (RuntimeException exception) {
        results.add(
            new EvaluationCaseResult(
                evaluationCase.caseId(),
                evaluationCase.category(),
                false,
                elapsedMillis(caseStarted),
                0,
                "HARNESS_CASE_EXECUTION_FAILED",
                List.of(exception.getClass().getSimpleName())));
      }
    }
    return new HarnessResult(
        metrics(dataset.cases(), observations, elapsedMillis(started)), List.copyOf(results));
  }

  private static EvaluationMetrics metrics(
      List<EvaluationCase> cases, List<CaseObservation> observations, long latencyMs) {
    long itineraryCases =
        cases.stream().filter(item -> "ITINERARY".equals(item.category())).count();
    long executable =
        observations.stream()
            .filter(item -> "ITINERARY".equals(item.evaluationCase().category()))
            .map(CaseObservation::observation)
            .filter(ReplayObservation::itineraryExecutable)
            .count();
    long conflictCases =
        observations.stream()
            .map(CaseObservation::observation)
            .filter(item -> item.detectedConflicts() > 0)
            .count();
    long evidenced =
        observations.stream()
            .map(CaseObservation::observation)
            .mapToLong(ReplayObservation::evidencedRecommendations)
            .sum();
    long recommendations =
        observations.stream()
            .map(CaseObservation::observation)
            .mapToLong(ReplayObservation::totalRecommendations)
            .sum();
    long successfulTools =
        observations.stream()
            .map(CaseObservation::observation)
            .mapToLong(ReplayObservation::successfulToolCalls)
            .sum();
    long toolCalls =
        observations.stream()
            .map(CaseObservation::observation)
            .mapToLong(ReplayObservation::totalToolCalls)
            .sum();
    long validOutputs =
        observations.stream()
            .map(CaseObservation::observation)
            .mapToLong(ReplayObservation::validStructuredOutputs)
            .sum();
    long outputs =
        observations.stream()
            .map(CaseObservation::observation)
            .mapToLong(ReplayObservation::totalStructuredOutputs)
            .sum();
    long successfulReplans =
        observations.stream()
            .map(CaseObservation::observation)
            .mapToLong(ReplayObservation::successfulReplans)
            .sum();
    long replans =
        observations.stream()
            .map(CaseObservation::observation)
            .mapToLong(ReplayObservation::totalReplans)
            .sum();
    long tokens =
        observations.stream()
            .map(CaseObservation::observation)
            .mapToLong(ReplayObservation::tokenUsage)
            .sum();
    return new EvaluationMetrics(
        rate(executable, itineraryCases),
        rate(conflictCases, observations.size()),
        rate(evidenced, recommendations),
        rate(successfulTools, toolCalls),
        rate(validOutputs, outputs),
        rate(successfulReplans, replans),
        latencyMs,
        tokens);
  }

  private static BigDecimal rate(long numerator, long denominator) {
    if (denominator == 0) {
      return BigDecimal.ZERO.setScale(4);
    }
    return BigDecimal.valueOf(numerator)
        .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
  }

  private static long elapsedMillis(long started) {
    return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
  }

  public record HarnessResult(EvaluationMetrics metrics, List<EvaluationCaseResult> cases) {

    public HarnessResult {
      cases = List.copyOf(cases);
    }
  }

  private record CaseObservation(EvaluationCase evaluationCase, ReplayObservation observation) {}
}
