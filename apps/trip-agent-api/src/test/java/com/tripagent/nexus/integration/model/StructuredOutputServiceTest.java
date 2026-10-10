package com.tripagent.nexus.integration.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelMetadata;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelResponse;
import com.tripagent.nexus.integration.model.ModelGatewayModels.StructuredModelResponse;
import com.tripagent.nexus.integration.model.ModelGatewayModels.TokenUsage;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult;
import com.tripagent.nexus.integration.model.result.CandidateRecommendationResult;
import com.tripagent.nexus.integration.model.result.PlanDraftResult;
import com.tripagent.nexus.integration.model.result.PlanReviewResult;
import com.tripagent.nexus.integration.model.result.QueryRewriteResult;
import com.tripagent.nexus.integration.model.result.RequirementUnderstandingResult;
import jakarta.validation.Validation;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StructuredOutputServiceTest {

  private static final ModelChatRequest REQUEST =
      new ModelChatRequest("Understand the request.", "Plan a Beijing trip.");
  private static final ModelMetadata METADATA =
      new ModelMetadata("deepseek", "test-model", new TokenUsage(10, 20, 30), false);

  private StructuredOutputService service;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    service =
        new StructuredOutputService(
            objectMapper, Validation.buildDefaultValidatorFactory().getValidator());
  }

  @ParameterizedTest
  @MethodSource("validStructuredOutputs")
  void deserializesEverySupportedResultType(Class<?> responseType, String json) {
    AtomicInteger calls = new AtomicInteger();

    StructuredModelResponse<?> result =
        service.execute(
            REQUEST,
            responseType,
            request -> {
              calls.incrementAndGet();
              assertThat(request.userPrompt()).contains("JSON Schema and format requirements:");
              return response(json);
            });

    assertThat(result.value()).isInstanceOf(responseType);
    assertThat(result.metadata()).isEqualTo(METADATA);
    assertThat(calls).hasValue(1);
  }

  @Test
  void repairsInvalidJsonExactlyOnce() {
    AtomicInteger calls = new AtomicInteger();

    StructuredModelResponse<QueryRewriteResult> result =
        service.execute(
            REQUEST,
            QueryRewriteResult.class,
            request -> {
              int call = calls.incrementAndGet();
              if (call == 1) {
                return response("{not-json");
              }
              assertThat(request.systemPrompt()).contains("Return only one JSON document");
              assertThat(request.userPrompt())
                  .contains("Repair the invalid JSON")
                  .contains("Validation errors:")
                  .contains("{not-json");
              return response(validQueryRewriteJson());
            });

    assertThat(result.value().rewrittenQuery()).isEqualTo("Beijing three day cultural itinerary");
    assertThat(calls).hasValue(2);
  }

  @Test
  void rejectsOutputThatRemainsInvalidAfterRepair() {
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                service.execute(
                    REQUEST,
                    QueryRewriteResult.class,
                    request -> {
                      calls.incrementAndGet();
                      return response("{still-not-json");
                    }))
        .isInstanceOfSatisfying(
            ModelGatewayException.class,
            exception -> {
              assertThat(exception.getCode()).isEqualTo("MODEL_OUTPUT_INVALID");
              assertThat(exception.isRetryable()).isFalse();
            });
    assertThat(calls).hasValue(2);
  }

  private static Stream<Arguments> validStructuredOutputs() {
    return Stream.of(
        Arguments.of(RequirementUnderstandingResult.class, validRequirementJson()),
        Arguments.of(BookingExtractionResult.class, validBookingJson()),
        Arguments.of(QueryRewriteResult.class, validQueryRewriteJson()),
        Arguments.of(CandidateRecommendationResult.class, validCandidateJson()),
        Arguments.of(PlanDraftResult.class, validPlanDraftJson()),
        Arguments.of(PlanReviewResult.class, validPlanReviewJson()));
  }

  private static ModelResponse response(String content) {
    return new ModelResponse(content, METADATA);
  }

  private static String validRequirementJson() {
    return """
        {
          "origin": "Shanghai",
          "destinations": ["Beijing"],
          "startDate": "2026-10-01",
          "endDate": "2026-10-03",
          "travelers": [
            {"type": "ADULT", "count": 1, "accessibilityNeeds": []}
          ],
          "totalBudget": 3000.0000,
          "currency": "CNY",
          "interests": ["culture"],
          "pace": "BALANCED",
          "mustVisit": ["Forbidden City"],
          "avoid": [],
          "constraints": ["No red-eye flights"],
          "assumptions": []
        }
        """;
  }

  private static String validBookingJson() {
    return """
        {
          "bookings": [
            {
              "type": "FLIGHT",
              "provider": "Example Air",
              "origin": "Shanghai Pudong",
              "destination": "Beijing Capital",
              "hotelName": "",
              "hotelAddress": "",
              "transportNumber": "MU5101",
              "confirmationNumber": "CONF-001",
              "startTime": "2026-10-01T01:00:00Z",
              "endTime": "2026-10-01T03:00:00Z",
              "travelerCount": 1,
              "amount": 1000.0000,
              "currency": "CNY",
              "confidence": 0.9500,
              "missingFields": [],
              "fieldEvidence": [
                {
                  "field": "transportNumber",
                  "confidence": 0.9500,
                  "textEvidence": "MU5101",
                  "region": {
                    "page": 1,
                    "x": 0.1000,
                    "y": 0.1000,
                    "width": 0.2000,
                    "height": 0.0500
                  }
                }
              ]
            }
          ],
          "warnings": [],
          "confidence": 0.9500,
          "parserMode": "DEEPSEEK",
          "parserVersion": "document-parser-1.0.0",
          "ocrEngine": "tesseract"
        }
        """;
  }

  private static String validQueryRewriteJson() {
    return """
        {
          "originalQuery": "Beijing trip",
          "rewrittenQuery": "Beijing three day cultural itinerary",
          "keywords": ["Beijing", "culture"],
          "graphEntities": ["Beijing"],
          "filters": {"durationDays": "3"}
        }
        """;
  }

  private static String validCandidateJson() {
    return """
        {
          "candidates": [
            {
              "candidateId": "candidate-1",
              "title": "Balanced Beijing",
              "reason": "Balances major sights and travel time.",
              "score": 0.9000,
              "evidenceIds": ["evidence-1"],
              "tradeoffs": ["Early morning start"]
            }
          ],
          "recommendationSummary": "Candidate 1 best matches the requested pace."
        }
        """;
  }

  private static String validPlanDraftJson() {
    return """
        {
          "title": "Beijing culture",
          "summary": "A three day culture-focused itinerary.",
          "days": [
            {
              "date": "2026-10-01",
              "city": "Beijing",
              "items": [
                {
                  "title": "Forbidden City",
                  "category": "ATTRACTION",
                  "startTime": "2026-10-01T09:00:00+08:00",
                  "endTime": "2026-10-01T12:00:00+08:00",
                  "location": "Beijing",
                  "estimatedCost": 60.0000,
                  "currency": "CNY",
                  "evidenceIds": ["evidence-1"]
                }
              ]
            }
          ],
          "budget": {
            "estimatedTotal": 60.0000,
            "budgetLimit": 3000.0000,
            "currency": "CNY"
          },
          "assumptions": []
        }
        """;
  }

  private static String validPlanReviewJson() {
    return """
        {
          "valid": true,
          "score": 0.9800,
          "conflicts": [],
          "repairSuggestions": []
        }
        """;
  }
}
