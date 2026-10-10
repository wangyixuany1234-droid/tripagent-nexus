package com.tripagent.nexus.integration.model;

import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelHealth;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelImage;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelMetadata;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelResponse;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelStreamChunk;
import com.tripagent.nexus.integration.model.ModelGatewayModels.MultimodalChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.TokenUsage;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class SpringAiDeepseekModelClient implements ModelProviderClient {

  private static final String PROVIDER = "deepseek";

  private final ChatClient chatClient;
  private final String model;

  public SpringAiDeepseekModelClient(
      ChatClient.Builder chatClientBuilder, ModelGatewayProperties properties) {
    this.chatClient = chatClientBuilder.build();
    this.model = properties.getModel();
  }

  @Override
  public ModelResponse chat(ModelChatRequest request) {
    ChatResponse response = requestSpec(request).call().chatResponse();
    return toResponse(response);
  }

  @Override
  public ModelResponse multimodalChat(MultimodalChatRequest request) {
    ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt();
    if (!request.systemPrompt().isBlank()) {
      requestSpec.system(request.systemPrompt());
    }
    requestSpec.user(
        user -> {
          user.text(request.userPrompt());
          request.images().forEach(image -> addImage(user, image));
        });
    return toResponse(requestSpec.call().chatResponse());
  }

  @Override
  public Flux<ModelStreamChunk> streamChat(ModelChatRequest request) {
    AtomicReference<ModelMetadata> lastMetadata =
        new AtomicReference<>(new ModelMetadata(PROVIDER, model, TokenUsage.unknown(), false));
    return requestSpec(request).stream()
        .chatResponse()
        .map(
            response -> {
              ModelMetadata metadata = metadata(response);
              lastMetadata.set(metadata);
              return new ModelStreamChunk(content(response), false, metadata);
            })
        .concatWith(Mono.fromSupplier(() -> new ModelStreamChunk("", true, lastMetadata.get())));
  }

  @Override
  public ModelHealth healthCheck() {
    try {
      ModelResponse response =
          chat(new ModelChatRequest("Reply with plain text only.", "Reply exactly: OK"));
      boolean available = !response.content().isBlank();
      return new ModelHealth(
          available, PROVIDER, model, available ? "available" : "empty response");
    } catch (RuntimeException exception) {
      return new ModelHealth(false, PROVIDER, model, exception.getClass().getSimpleName());
    }
  }

  private ChatClient.ChatClientRequestSpec requestSpec(ModelChatRequest request) {
    ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt();
    if (!request.systemPrompt().isBlank()) {
      requestSpec.system(request.systemPrompt());
    }
    return requestSpec.user(request.userPrompt());
  }

  private static void addImage(ChatClient.PromptUserSpec user, ModelImage image) {
    ByteArrayResource resource =
        new ByteArrayResource(image.data()) {
          @Override
          public String getFilename() {
            return "image";
          }
        };
    user.media(MimeTypeUtils.parseMimeType(image.mediaType()), resource);
  }

  private ModelResponse toResponse(ChatResponse response) {
    if (response == null) {
      throw new ModelGatewayException(
          "MODEL_EMPTY_RESPONSE", true, "The model provider returned no response.");
    }
    return new ModelResponse(content(response), metadata(response));
  }

  private ModelMetadata metadata(ChatResponse response) {
    Usage usage = response == null ? null : response.getMetadata().getUsage();
    TokenUsage tokenUsage =
        usage == null
            ? TokenUsage.unknown()
            : new TokenUsage(
                valueOrZero(usage.getPromptTokens()),
                valueOrZero(usage.getCompletionTokens()),
                valueOrZero(usage.getTotalTokens()));
    String responseModel =
        response == null || response.getMetadata().getModel() == null
            ? model
            : response.getMetadata().getModel();
    return new ModelMetadata(PROVIDER, responseModel, tokenUsage, false);
  }

  private static String content(ChatResponse response) {
    if (response == null
        || response.getResult() == null
        || response.getResult().getOutput() == null) {
      return "";
    }
    String text = response.getResult().getOutput().getText();
    return text == null ? "" : text;
  }

  private static long valueOrZero(Integer value) {
    return value == null ? 0 : value.longValue();
  }
}
