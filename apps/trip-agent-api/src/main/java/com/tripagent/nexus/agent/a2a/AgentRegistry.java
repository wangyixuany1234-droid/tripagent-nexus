package com.tripagent.nexus.agent.a2a;

import com.tripagent.nexus.agent.AgentDefinition;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.TripAgent;
import com.tripagent.nexus.skill.McpToolName;
import jakarta.annotation.PreDestroy;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.AgentCard;
import org.springframework.stereotype.Component;

@Component
public class AgentRegistry {

  private final Map<AgentName, Endpoint> endpoints = new EnumMap<>(AgentName.class);
  private final ExecutorService agentExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private final ExecutorService eventConsumerExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private final ExecutorService eventBusExecutor = Executors.newCachedThreadPool();

  public AgentRegistry(List<TripAgent> agents) {
    for (TripAgent agent : agents) {
      AgentDefinition<?, ?> definition = agent.definition();
      validateDefinition(definition);
      if (endpoints.containsKey(definition.name())) {
        throw new IllegalStateException("Duplicate Agent registration: " + definition.name());
      }
      endpoints.put(definition.name(), createEndpoint(agent));
    }
  }

  public List<AgentCard> discover(Set<String> requiredSkills) {
    return endpoints.values().stream()
        .map(endpoint -> endpoint.agent().definition().card())
        .filter(
            card ->
                requiredSkills.isEmpty()
                    || card.skills().stream()
                        .map(skill -> skill.id())
                        .collect(java.util.stream.Collectors.toSet())
                        .containsAll(requiredSkills))
        .toList();
  }

  public Endpoint require(AgentName name) {
    Endpoint endpoint = endpoints.get(name);
    if (endpoint == null) {
      throw new IllegalArgumentException("Agent is not registered: " + name.cardName());
    }
    return endpoint;
  }

  public Set<AgentName> registeredAgents() {
    return Set.copyOf(endpoints.keySet());
  }

  private static void validateDefinition(AgentDefinition<?, ?> definition) {
    if (!definition.name().cardName().equals(definition.card().name())) {
      throw new IllegalStateException("Agent Card identity does not match the registration.");
    }
    Set<String> advertised =
        definition.card().skills().stream()
            .map(skill -> skill.id())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    Set<String> allowed =
        definition.skillAllowlist().stream()
            .map(McpToolName::value)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    if (!advertised.equals(allowed)) {
      throw new IllegalStateException("Agent Card capabilities do not match the tool allowlist.");
    }
  }

  @PreDestroy
  void close() {
    agentExecutor.shutdownNow();
    eventConsumerExecutor.shutdownNow();
    eventBusExecutor.shutdownNow();
  }

  private Endpoint createEndpoint(TripAgent agent) {
    TenantTaskAuthorizationProvider authorization = new TenantTaskAuthorizationProvider();
    InMemoryTaskStore taskStore = new InMemoryTaskStore(authorization);
    MainEventBus eventBus = new MainEventBus();
    InMemoryQueueManager queueManager = new InMemoryQueueManager(taskStore, eventBus);
    MainEventBusProcessor processor =
        new MainEventBusProcessor(eventBus, taskStore, (event, task) -> {}, queueManager);
    eventBusExecutor.submit(processor);
    DefaultRequestHandler handler =
        DefaultRequestHandler.builder()
            .agentExecutor(agent)
            .taskStore(taskStore)
            .queueManager(queueManager)
            .mainEventBusProcessor(processor)
            .executor(agentExecutor)
            .eventConsumerExecutor(eventConsumerExecutor)
            .authorizationProvider(authorization)
            .authorizationRequired(true)
            .pushNotificationsEnabled(false)
            .build();
    return new Endpoint(agent, handler, taskStore);
  }

  public record Endpoint(
      TripAgent agent, DefaultRequestHandler requestHandler, TaskStore taskStore) {}
}
