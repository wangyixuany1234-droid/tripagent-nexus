package com.tripagent.nexus.integration.model;

import com.tripagent.nexus.agent.accommodation.AccommodationAgentModels;
import com.tripagent.nexus.agent.recommendation.RecommendationAgentModels;
import com.tripagent.nexus.agent.requirement.RequirementAgentModels;
import com.tripagent.nexus.agent.research.ResearchAgentModels;
import com.tripagent.nexus.agent.review.ReviewAgentModels;
import com.tripagent.nexus.agent.transport.TransportAgentModels;
import com.tripagent.nexus.domain.model.DomainEnums.Pace;
import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelHealth;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelMetadata;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelResponse;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelStreamChunk;
import com.tripagent.nexus.integration.model.ModelGatewayModels.MultimodalChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.StructuredModelResponse;
import com.tripagent.nexus.integration.model.ModelGatewayModels.TokenUsage;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult;
import com.tripagent.nexus.integration.model.result.CandidateRecommendationResult;
import com.tripagent.nexus.integration.model.result.PlanDraftResult;
import com.tripagent.nexus.integration.model.result.PlanReviewResult;
import com.tripagent.nexus.integration.model.result.QueryRewriteResult;
import com.tripagent.nexus.integration.model.result.RequirementUnderstandingResult;
import com.tripagent.nexus.observability.TraceOperations;
import io.micrometer.tracing.Span;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

public class MockModelGateway implements ModelGateway {

  private static final Logger log = LoggerFactory.getLogger(MockModelGateway.class);
  private static final String PROVIDER = "mock";

  private final String model;
  private final Map<Class<?>, Object> structuredFixtures;
  private final TraceOperations traces;

  public MockModelGateway(ModelGatewayProperties properties) {
    this(properties, null);
  }

  public MockModelGateway(ModelGatewayProperties properties, TraceOperations traces) {
    this.model = "mock-" + properties.getModel();
    this.structuredFixtures = defaultFixtures();
    this.traces = traces;
  }

  @Override
  public ModelResponse chat(ModelChatRequest request) {
    return observed(
        "chat",
        request.promptVersion(),
        () -> {
          log.info("Model request completed provider=mock operation=chat");
          return new ModelResponse("Mock model response", metadata());
        });
  }

  @Override
  public <T> StructuredModelResponse<T> structuredChat(
      ModelChatRequest request, Class<T> responseType) {
    return observed(
        "structured_chat",
        request.promptVersion(),
        () -> {
          Object fixture = structuredFixtures.get(responseType);
          if (fixture == null) {
            throw new ModelGatewayException(
                "MODEL_MOCK_TYPE_UNSUPPORTED",
                false,
                "The mock model has no fixture for " + responseType.getSimpleName() + ".");
          }
          log.info(
              "Model request completed provider=mock operation=structuredChat type={}",
              responseType.getSimpleName());
          return new StructuredModelResponse<>(responseType.cast(fixture), metadata());
        });
  }

  @Override
  public ModelResponse multimodalChat(MultimodalChatRequest request) {
    return observed(
        "multimodal",
        request.promptVersion(),
        () -> {
          log.info(
              "Model request completed provider=mock operation=multimodalChat imageCount={}",
              request.images().size());
          return new ModelResponse("Mock multimodal response", metadata());
        });
  }

  @Override
  public Flux<ModelStreamChunk> streamChat(ModelChatRequest request) {
    if (traces == null) {
      return mockStream();
    }
    return traces.traceFlux(
        "model.call",
        "tripagent.model.latency",
        attributes("stream", request.promptVersion()),
        this::mockStream,
        (span, chunk) -> tagUsage(span, chunk.metadata()));
  }

  @Override
  public ModelHealth healthCheck() {
    return new ModelHealth(true, PROVIDER, model, "available");
  }

  private ModelMetadata metadata() {
    return new ModelMetadata(PROVIDER, model, TokenUsage.unknown(), false);
  }

  private Flux<ModelStreamChunk> mockStream() {
    log.info("Model request completed provider=mock operation=streamChat");
    return Flux.just(
        new ModelStreamChunk("Mock model response", false, metadata()),
        new ModelStreamChunk("", true, metadata()));
  }

  private <T> T observed(
      String operation, String promptVersion, java.util.function.Supplier<T> call) {
    if (traces == null) {
      return call.get();
    }
    return traces.trace(
        "model.call",
        "tripagent.model.latency",
        attributes(operation, promptVersion),
        call,
        (span, result) -> {
          if (result instanceof ModelResponse response) {
            tagUsage(span, response.metadata());
          } else if (result instanceof StructuredModelResponse<?> response) {
            tagUsage(span, response.metadata());
          }
        });
  }

  private static void tagUsage(Span span, ModelMetadata metadata) {
    TokenUsage usage = metadata.tokenUsage();
    span.tag("gen_ai.response.model", metadata.model());
    span.tag("gen_ai.usage.input_tokens", Long.toString(usage.inputTokens()));
    span.tag("gen_ai.usage.output_tokens", Long.toString(usage.outputTokens()));
    span.tag("gen_ai.usage.total_tokens", Long.toString(usage.totalTokens()));
  }

  private Map<String, String> attributes(String operation, String promptVersion) {
    return Map.of(
        "gen_ai.operation.name", operation,
        "gen_ai.provider.name", PROVIDER,
        "gen_ai.request.model", model,
        "gen_ai.prompt.version", promptVersion);
  }

  private static Map<Class<?>, Object> defaultFixtures() {
    RequirementUnderstandingResult requirement =
        new RequirementUnderstandingResult(
            "Beijing",
            List.of("Beijing"),
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 3),
            List.of(new RequirementUnderstandingResult.TravelerSummary("ADULT", 1, List.of())),
            new BigDecimal("3000.0000"),
            "CNY",
            List.of("culture"),
            Pace.BALANCED,
            List.of(),
            List.of(),
            List.of(),
            List.of("Mock fixture"));
    BookingExtractionResult booking =
        new BookingExtractionResult(
            List.of(
                new BookingExtractionResult.ExtractedBooking(
                    com.tripagent.nexus.domain.model.DomainEnums.BookingType.OTHER,
                    "Mock Provider",
                    null,
                    "Mock Destination",
                    null,
                    null,
                    null,
                    "MOCK-CONFIRMATION",
                    Instant.parse("2026-10-01T01:00:00Z"),
                    Instant.parse("2026-10-01T02:00:00Z"),
                    1,
                    BigDecimal.ZERO,
                    "CNY",
                    BigDecimal.ONE,
                    List.of(),
                    List.of())),
            List.of("Mock parser fixture"),
            BigDecimal.ONE,
            "MOCK",
            "mock-1",
            "mock");
    QueryRewriteResult rewrite =
        new QueryRewriteResult(
            "Beijing trip",
            "Beijing three day cultural itinerary",
            List.of("Beijing", "culture"),
            List.of("Beijing"),
            Map.of("durationDays", "3"));
    CandidateRecommendationResult candidate =
        new CandidateRecommendationResult(
            List.of(
                new CandidateRecommendationResult.Candidate(
                    "mock-candidate-1",
                    "Balanced Beijing",
                    "Balances major sights and travel time.",
                    new BigDecimal("0.9000"),
                    List.of("mock-evidence-1"),
                    List.of("Mock data only"))),
            "Mock recommendation");
    PlanDraftResult draft =
        new PlanDraftResult(
            "Mock Beijing plan",
            "Mock data for tests only.",
            List.of(
                new PlanDraftResult.DraftDay(
                    LocalDate.of(2026, 10, 1),
                    "Beijing",
                    List.of(
                        new PlanDraftResult.DraftItem(
                            "Forbidden City",
                            "ATTRACTION",
                            OffsetDateTime.parse("2026-10-01T09:00:00+08:00"),
                            OffsetDateTime.parse("2026-10-01T12:00:00+08:00"),
                            "Beijing",
                            new BigDecimal("60.0000"),
                            "CNY",
                            List.of("mock-evidence-1"))))),
            new PlanDraftResult.DraftBudget(
                new BigDecimal("60.0000"), new BigDecimal("3000.0000"), "CNY"),
            List.of("Mock fixture"));
    PlanReviewResult review = new PlanReviewResult(true, BigDecimal.ONE, List.of(), List.of());
    RequirementAgentModels.Output requirementAgent =
        new RequirementAgentModels.Output(
            "Shanghai",
            List.of("Beijing"),
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 3),
            new BigDecimal("3000.0000"),
            "CNY",
            List.of("Travel dates are fixed", "Budget limit is CNY 3000"),
            List.of("Prefer cultural attractions"),
            List.of());
    ResearchAgentModels.Output researchAgent =
        new ResearchAgentModels.Output(
            "Mock destination research for Beijing.", List.of("Dongcheng", "Xicheng"), false);
    TransportAgentModels.Output transportAgent =
        new TransportAgentModels.Output(
            List.of(
                new TransportAgentModels.TransportOption(
                    "mock-transport-1",
                    "TRAIN",
                    "Shanghai",
                    "Beijing",
                    OffsetDateTime.parse("2026-10-01T06:00:00+08:00"),
                    OffsetDateTime.parse("2026-10-01T10:00:00+08:00"),
                    new BigDecimal("500.0000"),
                    "CNY")));
    AccommodationAgentModels.Output accommodationAgent =
        new AccommodationAgentModels.Output(
            OffsetDateTime.parse("2026-10-01T10:00:00+08:00"),
            List.of(
                new AccommodationAgentModels.StayOption(
                    "mock-stay-1", "Mock Hotel", "Dongcheng", new BigDecimal("1200.0000"), "CNY")));
    RecommendationAgentModels.Output recommendationAgent =
        new RecommendationAgentModels.Output(
            List.of(
                new RecommendationAgentModels.Recommendation(
                    "mock-attraction-1",
                    RecommendationAgentModels.RecommendationType.ATTRACTION,
                    "Forbidden City",
                    "Dongcheng",
                    new BigDecimal("39.9163447"),
                    new BigDecimal("116.3971546"),
                    new BigDecimal("60.0000"),
                    "CNY"),
                new RecommendationAgentModels.Recommendation(
                    "mock-restaurant-1",
                    RecommendationAgentModels.RecommendationType.RESTAURANT,
                    "Mock Restaurant",
                    "Dongcheng",
                    new BigDecimal("39.9234290"),
                    new BigDecimal("116.4175710"),
                    new BigDecimal("200.0000"),
                    "CNY")),
            false);
    ReviewAgentModels.Output reviewAgent = new ReviewAgentModels.Output(true, 1, List.of());
    return Map.ofEntries(
        Map.entry(RequirementUnderstandingResult.class, requirement),
        Map.entry(BookingExtractionResult.class, booking),
        Map.entry(QueryRewriteResult.class, rewrite),
        Map.entry(CandidateRecommendationResult.class, candidate),
        Map.entry(PlanDraftResult.class, draft),
        Map.entry(PlanReviewResult.class, review),
        Map.entry(RequirementAgentModels.Output.class, requirementAgent),
        Map.entry(ResearchAgentModels.Output.class, researchAgent),
        Map.entry(TransportAgentModels.Output.class, transportAgent),
        Map.entry(AccommodationAgentModels.Output.class, accommodationAgent),
        Map.entry(RecommendationAgentModels.Output.class, recommendationAgent),
        Map.entry(ReviewAgentModels.Output.class, reviewAgent));
  }
}
