package com.tripagent.nexus.integration.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelImage;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelResponse;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelStreamChunk;
import com.tripagent.nexus.integration.model.ModelGatewayModels.MultimodalChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.StructuredModelResponse;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult;
import com.tripagent.nexus.integration.model.result.CandidateRecommendationResult;
import com.tripagent.nexus.integration.model.result.PlanDraftResult;
import com.tripagent.nexus.integration.model.result.PlanReviewResult;
import com.tripagent.nexus.integration.model.result.QueryRewriteResult;
import com.tripagent.nexus.integration.model.result.RequirementUnderstandingResult;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MockModelGatewayTest {

  private static final ModelChatRequest REQUEST = new ModelChatRequest("", "test request");

  private MockModelGateway gateway;

  @BeforeEach
  void setUp() {
    ModelGatewayProperties properties = new ModelGatewayProperties();
    properties.setModel("test-model");
    gateway = new MockModelGateway(properties);
  }

  @ParameterizedTest
  @MethodSource("supportedResultTypes")
  void returnsDeterministicFixturesWithMockMetadata(Class<?> responseType) {
    StructuredModelResponse<?> response = gateway.structuredChat(REQUEST, responseType);

    assertThat(response.value()).isInstanceOf(responseType);
    assertThat(response.metadata().provider()).isEqualTo("mock");
    assertThat(response.metadata().model()).isEqualTo("mock-test-model");
    assertThat(response.metadata().fallback()).isFalse();
  }

  @Test
  void marksChatMultimodalStreamAndHealthAsMock() {
    ModelResponse chat = gateway.chat(REQUEST);
    ModelResponse multimodal =
        gateway.multimodalChat(
            new MultimodalChatRequest(
                "", "read image", List.of(new ModelImage("image/png", new byte[] {1, 2, 3}))));
    List<ModelStreamChunk> chunks = gateway.streamChat(REQUEST).collectList().block();

    assertThat(chat.metadata().provider()).isEqualTo("mock");
    assertThat(multimodal.metadata().provider()).isEqualTo("mock");
    assertThat(chunks).isNotEmpty().allMatch(chunk -> chunk.metadata().provider().equals("mock"));
    assertThat(gateway.healthCheck().provider()).isEqualTo("mock");
  }

  @Test
  void rejectsUnsupportedStructuredType() {
    assertThatThrownBy(() -> gateway.structuredChat(REQUEST, String.class))
        .isInstanceOfSatisfying(
            ModelGatewayException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("MODEL_MOCK_TYPE_UNSUPPORTED"));
  }

  private static Stream<Class<?>> supportedResultTypes() {
    return Stream.of(
        RequirementUnderstandingResult.class,
        BookingExtractionResult.class,
        QueryRewriteResult.class,
        CandidateRecommendationResult.class,
        PlanDraftResult.class,
        PlanReviewResult.class);
  }
}
