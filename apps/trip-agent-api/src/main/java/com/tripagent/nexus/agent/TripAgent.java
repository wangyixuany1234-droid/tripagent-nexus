package com.tripagent.nexus.agent;

import org.a2aproject.sdk.server.agentexecution.AgentExecutor;

public interface TripAgent extends AgentExecutor {

  AgentDefinition<?, ?> definition();
}
