package com.tripagent.nexus.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.integration.model.ModelGateway;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelChatRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.agents.enabled", havingValue = "true", matchIfMissing = true)
public class AgentModelInvoker {

  private final ModelGateway modelGateway;
  private final ObjectMapper objectMapper;

  public AgentModelInvoker(ModelGateway modelGateway, ObjectMapper objectMapper) {
    this.modelGateway = modelGateway;
    this.objectMapper = objectMapper;
  }

  public <T> T invoke(String promptVersion, Object input, Class<T> outputType) {
    try {
      String inputJson = objectMapper.writeValueAsString(input);
      return modelGateway
          .structuredChat(
              new ModelChatRequest(AgentPromptLoader.load(promptVersion), inputJson, promptVersion),
              outputType)
          .value();
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Agent input could not be serialized.", exception);
    }
  }
}
