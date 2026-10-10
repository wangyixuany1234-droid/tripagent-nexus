package com.tripagent.nexus.integration.model;

import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelChatRequest;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelHealth;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelResponse;
import com.tripagent.nexus.integration.model.ModelGatewayModels.ModelStreamChunk;
import com.tripagent.nexus.integration.model.ModelGatewayModels.MultimodalChatRequest;
import reactor.core.publisher.Flux;

public interface ModelProviderClient {

  ModelResponse chat(ModelChatRequest request);

  ModelResponse multimodalChat(MultimodalChatRequest request);

  Flux<ModelStreamChunk> streamChat(ModelChatRequest request);

  ModelHealth healthCheck();
}
