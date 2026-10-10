package com.tripagent.nexus.integration.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.tripagent.nexus.infrastructure.config.ModelGatewayProperties;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelImage;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelResponse;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelStreamChunk;
import com.tripagent.nexus.integration.model.ModelGatewayModels.MultimodalChatRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class SpringAiDeepseekModelClientTest {

  private CapturingChatModel chatModel;
  private SpringAiDeepseekModelClient client;

  @BeforeEach
  void setUp() {
    chatModel = new CapturingChatModel(response("answer"));
    ModelGatewayProperties properties = new ModelGatewayProperties();
    properties.setModel("configured-model");
    client = new SpringAiDeepseekModelClient(ChatClient.builder(chatModel), properties);
  }

  @Test
  void mapsTextResponseModelAndTokenUsage() {
    ModelResponse result = client.chat(new ModelChatRequest("system instruction", "user request"));

    assertThat(result.content()).isEqualTo("answer");
    assertThat(result.metadata().provider()).isEqualTo("deepseek");
    assertThat(result.metadata().model()).isEqualTo("response-model");
    assertThat(result.metadata().tokenUsage().inputTokens()).isEqualTo(2);
    assertThat(result.metadata().tokenUsage().outputTokens()).isEqualTo(3);
    assertThat(result.metadata().tokenUsage().totalTokens()).isEqualTo(5);
    assertThat(chatModel.lastPrompt.getSystemMessage().getText()).isEqualTo("system instruction");
    assertThat(chatModel.lastPrompt.getUserMessage().getText()).isEqualTo("user request");
  }

  @Test
  void sendsImagesThroughSpringAiMultimodalPrompt() {
    byte[] image = {1, 2, 3};

    client.multimodalChat(
        new MultimodalChatRequest(
            "extract booking", "read this image", List.of(new ModelImage("image/png", image))));

    assertThat(chatModel.lastPrompt.getUserMessage().getText()).isEqualTo("read this image");
    assertThat(chatModel.lastPrompt.getUserMessage().getMedia()).hasSize(1);
  }

  @Test
  void mapsStreamingContentAndAppendsTerminalChunk() {
    List<ModelStreamChunk> chunks =
        client.streamChat(new ModelChatRequest("", "stream request")).collectList().block();

    assertThat(chunks).hasSize(2);
    assertThat(chunks.get(0).content()).isEqualTo("answer");
    assertThat(chunks.get(0).done()).isFalse();
    assertThat(chunks.get(1).content()).isEmpty();
    assertThat(chunks.get(1).done()).isTrue();
    assertThat(chunks.get(1).metadata().tokenUsage().totalTokens()).isEqualTo(5);
  }

  @Test
  void reportsAvailableHealthForNonEmptyResponse() {
    assertThat(client.healthCheck().available()).isTrue();
    assertThat(client.healthCheck().provider()).isEqualTo("deepseek");
  }

  private static ChatResponse response(String content) {
    return new ChatResponse(
        List.of(new Generation(new AssistantMessage(content))),
        ChatResponseMetadata.builder()
            .model("response-model")
            .usage(new DefaultUsage(2, 3, 5))
            .build());
  }

  private static final class CapturingChatModel implements ChatModel {

    private final ChatResponse response;
    private Prompt lastPrompt;

    private CapturingChatModel(ChatResponse response) {
      this.response = response;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      lastPrompt = prompt;
      return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      lastPrompt = prompt;
      return Flux.just(response);
    }
  }
}
