package com.tripagent.nexus.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.evaluation.EvaluationModels.EvaluationCase;
import com.tripagent.nexus.evaluation.EvaluationModels.EvaluationDataset;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class EvaluationDatasetLoader {

  private static final String SUPPORTED_VERSION = "phase-9-v1";
  private final ObjectMapper objectMapper;

  public EvaluationDatasetLoader(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public EvaluationDataset load(String version, List<String> selectedCaseIds) {
    if (!SUPPORTED_VERSION.equals(version)) {
      throw new ApiException(
          "EVALUATION_DATASET_NOT_FOUND",
          HttpStatus.NOT_FOUND,
          "The requested evaluation dataset version is not available.");
    }
    EvaluationDataset dataset = read(version);
    if (selectedCaseIds == null || selectedCaseIds.isEmpty()) {
      return dataset;
    }
    Set<String> selected = Set.copyOf(selectedCaseIds);
    List<EvaluationCase> cases =
        dataset.cases().stream().filter(item -> selected.contains(item.caseId())).toList();
    Set<String> found = new HashSet<>();
    cases.forEach(item -> found.add(item.caseId()));
    if (!found.equals(selected)) {
      throw new ApiException(
          "EVALUATION_CASE_NOT_FOUND",
          HttpStatus.UNPROCESSABLE_ENTITY,
          "One or more selected evaluation cases do not exist in the dataset.");
    }
    return new EvaluationDataset(dataset.version(), cases);
  }

  private EvaluationDataset read(String version) {
    try {
      var resource = new ClassPathResource("evals/" + version + ".json");
      return objectMapper.readValue(resource.getInputStream(), EvaluationDataset.class);
    } catch (IOException exception) {
      throw new IllegalStateException("Evaluation dataset could not be loaded.", exception);
    }
  }
}
