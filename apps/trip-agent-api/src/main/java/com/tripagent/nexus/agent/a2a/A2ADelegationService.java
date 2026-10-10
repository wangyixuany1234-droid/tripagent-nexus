package com.tripagent.nexus.agent.a2a;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripagent.nexus.agent.AgentContracts.AgentInput;
import com.tripagent.nexus.agent.AgentContracts.AgentResultEnvelope;
import com.tripagent.nexus.agent.AgentContracts.AgentWarning;
import com.tripagent.nexus.agent.AgentDefinition;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.AgentSchemaValidator;
import com.tripagent.nexus.agent.AgentSchemaValidator.AgentValidationException;
import com.tripagent.nexus.agent.AgentStatus;
import com.tripagent.nexus.agent.ContextEnvelope;
import com.tripagent.nexus.agent.ContextEnvelopeReducer;
import com.tripagent.nexus.agent.coordinator.CoordinatorAgentModels;
import com.tripagent.nexus.application.planning.AgentCheckpointService;
import com.tripagent.nexus.application.service.AgentExecutionService;
import com.tripagent.nexus.observability.TraceContextProvider;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.User;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskState;
import org.springframework.stereotype.Service;

@Service
public class A2ADelegationService {

  private static final TypeReference<AgentResultEnvelope<JsonNode>> RESULT_TYPE =
      new TypeReference<>() {};
  private static final Duration TASK_POLL_INTERVAL = Duration.ofMillis(25);
  private static final int MAX_RESULT_BYTES = 1_000_000;

  private final AgentRegistry registry;
  private final ContextEnvelopeReducer contextReducer;
  private final AgentSchemaValidator schemaValidator;
  private final ObjectMapper objectMapper;
  private final TraceContextProvider traceContextProvider;
  private final Tracer tracer;
  private final AgentExecutionService executionService;
  private final AgentCheckpointService checkpoints;
  private final ExecutorService delegationExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public A2ADelegationService(
      AgentRegistry registry,
      ContextEnvelopeReducer contextReducer,
      AgentSchemaValidator schemaValidator,
      ObjectMapper objectMapper,
      TraceContextProvider traceContextProvider,
      Tracer tracer,
      AgentExecutionService executionService,
      AgentCheckpointService checkpoints) {
    this.registry = registry;
    this.contextReducer = contextReducer;
    this.schemaValidator = schemaValidator;
    this.objectMapper = objectMapper;
    this.traceContextProvider = traceContextProvider;
    this.tracer = tracer;
    this.executionService = executionService;
    this.checkpoints = checkpoints;
  }

  public List<org.a2aproject.sdk.spec.AgentCard> discover(Set<String> requiredSkills) {
    return registry.discover(requiredSkills);
  }

  public AgentResultEnvelope<JsonNode> delegate(
      AgentName target, AgentInput input, String tenantId) {
    UUID token =
        input instanceof CoordinatorAgentModels.Input coordinator
            ? coordinator.executionToken()
            : null;
    return delegateStep(target, input, tenantId, executionTaskId(input.taskId(), tenantId), token);
  }

  public AgentResultEnvelope<JsonNode> delegateStep(
      AgentName target, AgentInput input, String tenantId, UUID ownerTaskId, UUID token) {
    AgentRegistry.Endpoint endpoint = registry.require(target);
    AgentDefinition<?, ?> definition = endpoint.agent().definition();
    AgentInput minimized = minimize(input, definition);
    AgentCheckpointService.Claim claim =
        token == null
            ? null
            : checkpoints.begin(ownerTaskId, token, definition, minimized, tenantId);
    if (claim != null && claim.result() != null) {
      return claim.result();
    }
    UUID executionId =
        claim == null ? executionService.start(ownerTaskId, target) : claim.executionId();
    long startedNanos = System.nanoTime();
    Span span =
        tracer
            .nextSpan()
            .name("a2a.delegate")
            .tag("agent.name", target.cardName())
            .tag("agent.task.id", input.taskId().toString())
            .tag("gen_ai.prompt.version", definition.policy().promptTemplateVersion())
            .start();
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      String traceId = span.context().traceId();
      Message message =
          Message.builder()
              .role(Message.Role.ROLE_USER)
              .parts(new DataPart(objectMapper.convertValue(minimized, Object.class)))
              .messageId(UUID.randomUUID().toString())
              .contextId(input.taskId().toString())
              .metadata(
                  Map.of(
                      "agentName", target.cardName(),
                      "promptTemplateVersion", definition.policy().promptTemplateVersion()))
              .build();
      String tenantRef = tenantReference(tenantId);
      MessageSendParams params =
          MessageSendParams.builder()
              .message(message)
              .configuration(
                  MessageSendConfiguration.builder()
                      .acceptedOutputModes(List.of("application/json"))
                      .historyLength(0)
                      .returnImmediately(true)
                      .build())
              .metadata(
                  Map.of(
                      "traceId",
                      traceId,
                      "schemaVersion",
                      "1.0.0",
                      "ownerTaskId",
                      ownerTaskId.toString()))
              .tenant(tenantRef)
              .build();
      ServerCallContext callContext = callContext(tenantRef);
      AgentResultEnvelope<JsonNode> result =
          executeWithPolicy(
              target, input.taskId(), endpoint, params, callContext, tenantRef, definition);
      if (claim == null) {
        executionService.complete(executionId, result, elapsedMillis(startedNanos));
      } else {
        checkpoints.complete(
            ownerTaskId, token, executionId, definition, result, elapsedMillis(startedNanos));
      }
      span.tag("agent.status", result.status().name());
      span.tag(
          "operation.success",
          Boolean.toString(
              result.status() == AgentStatus.SUCCEEDED || result.status() == AgentStatus.DEGRADED));
      if (result.status() == AgentStatus.FAILED || result.status() == AgentStatus.TIMEOUT) {
        span.tag(
            "error.type",
            result.warnings().isEmpty()
                ? "A2A_DELEGATION_FAILED"
                : result.warnings().getFirst().code());
      }
      return result;
    } catch (RuntimeException exception) {
      if (claim == null)
        executionService.fail(executionId, "A2A_DELEGATION_FAILED", elapsedMillis(startedNanos));
      span.tag("operation.success", "false");
      span.tag("error.type", exception.getClass().getSimpleName());
      span.error(exception);
      throw exception;
    } finally {
      span.end();
    }
  }

  public Task taskStatus(AgentName agentName, String a2aTaskId, String tenantId) {
    return registry
        .require(agentName)
        .requestHandler()
        .onGetTask(
            TaskQueryParams.builder()
                .id(a2aTaskId)
                .historyLength(0)
                .tenant(tenantReference(tenantId))
                .build(),
            callContext(tenantReference(tenantId)));
  }

  @PreDestroy
  void close() {
    delegationExecutor.shutdownNow();
  }

  private AgentResultEnvelope<JsonNode> executeWithPolicy(
      AgentName target,
      UUID logicalTaskId,
      AgentRegistry.Endpoint endpoint,
      MessageSendParams params,
      ServerCallContext callContext,
      String tenantRef,
      AgentDefinition<?, ?> definition) {
    int maxAttempts = definition.policy().retryPolicy().maxAttempts();
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      AtomicReference<String> delegatedTaskId = new AtomicReference<>();
      Future<EventKind> future =
          delegationExecutor.submit(
              () -> awaitTerminalEvent(endpoint, params, callContext, tenantRef, delegatedTaskId));
      try {
        EventKind event =
            future.get(
                Math.max(1, definition.policy().timeout().toMillis()), TimeUnit.MILLISECONDS);
        if (!(event instanceof Task task)) {
          return failure(
              logicalTaskId,
              target,
              AgentStatus.FAILED,
              "A2A_RESULT_INVALID",
              "Agent did not return a task result.");
        }
        if (delegatedTaskId.get() != null && !delegatedTaskId.get().equals(task.id())) {
          return failure(
              logicalTaskId,
              target,
              AgentStatus.FAILED,
              "A2A_TASK_ASSOCIATION_INVALID",
              "Agent task identity changed during delegation.");
        }
        if (!logicalTaskId.toString().equals(task.contextId())) {
          return failure(
              logicalTaskId,
              target,
              AgentStatus.FAILED,
              "A2A_TASK_ASSOCIATION_INVALID",
              "Agent task context does not match the delegation.");
        }
        if (task.status().state() == TaskState.TASK_STATE_COMPLETED) {
          return extract(task, logicalTaskId, definition);
        }
        if (task.status().state() == TaskState.TASK_STATE_FAILED) {
          return failure(
              logicalTaskId, target, AgentStatus.FAILED, "A2A_AGENT_FAILED", "Agent task failed.");
        }
        return failure(
            logicalTaskId,
            target,
            AgentStatus.TIMEOUT,
            "A2A_AGENT_TIMEOUT",
            "Agent did not reach a terminal state before its deadline.");
      } catch (TimeoutException exception) {
        future.cancel(true);
        cancelDelegatedTask(endpoint, delegatedTaskId.get(), tenantRef, callContext);
        return failure(
            logicalTaskId,
            target,
            AgentStatus.TIMEOUT,
            "A2A_AGENT_TIMEOUT",
            "Agent did not finish before its deadline.");
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        future.cancel(true);
        cancelDelegatedTask(endpoint, delegatedTaskId.get(), tenantRef, callContext);
        return failure(
            logicalTaskId,
            target,
            AgentStatus.FAILED,
            "A2A_DELEGATION_INTERRUPTED",
            "Agent delegation was interrupted.");
      } catch (ExecutionException exception) {
        if (attempt == maxAttempts) {
          return failure(
              logicalTaskId,
              target,
              AgentStatus.FAILED,
              errorCode(exception.getCause()),
              "Agent delegation failed.");
        }
        sleep(backoff(definition, attempt));
      } catch (RuntimeException exception) {
        if (attempt == maxAttempts) {
          return failure(
              logicalTaskId,
              target,
              AgentStatus.FAILED,
              errorCode(exception),
              "Agent delegation failed.");
        }
        sleep(backoff(definition, attempt));
      }
    }
    return failure(
        logicalTaskId,
        target,
        AgentStatus.FAILED,
        "A2A_DELEGATION_FAILED",
        "Agent delegation failed.");
  }

  private EventKind awaitTerminalEvent(
      AgentRegistry.Endpoint endpoint,
      MessageSendParams params,
      ServerCallContext callContext,
      String tenantRef,
      AtomicReference<String> delegatedTaskId)
      throws InterruptedException {
    EventKind event = endpoint.requestHandler().onMessageSend(params, callContext);
    if (!(event instanceof Task task)) {
      return event;
    }
    delegatedTaskId.set(task.id());
    while (!task.status().state().isFinal() && !task.status().state().isInterrupted()) {
      Thread.sleep(TASK_POLL_INTERVAL);
      task =
          endpoint
              .requestHandler()
              .onGetTask(
                  TaskQueryParams.builder()
                      .id(task.id())
                      .historyLength(0)
                      .tenant(tenantRef)
                      .build(),
                  callContext);
    }
    return task;
  }

  private static void cancelDelegatedTask(
      AgentRegistry.Endpoint endpoint,
      String taskId,
      String tenantRef,
      ServerCallContext callContext) {
    if (taskId == null) {
      return;
    }
    try {
      endpoint
          .requestHandler()
          .onCancelTask(
              CancelTaskParams.builder().id(taskId).tenant(tenantRef).build(), callContext);
    } catch (RuntimeException ignored) {
      // The task may have completed concurrently with the deadline.
    }
  }

  private AgentResultEnvelope<JsonNode> extract(
      Task task, UUID logicalTaskId, AgentDefinition<?, ?> definition) {
    if (task.artifacts() == null || task.artifacts().isEmpty()) {
      throw new AgentValidationException(
          "A2A_RESULT_INVALID", "A2A task did not return artifacts.");
    }
    List<DataPart> parts =
        task.artifacts().stream()
            .filter(artifact -> artifact.parts() != null)
            .flatMap(artifact -> artifact.parts().stream())
            .filter(DataPart.class::isInstance)
            .map(DataPart.class::cast)
            .toList();
    if (parts.size() != 1) {
      throw new AgentValidationException(
          "A2A_RESULT_INVALID", "A2A task must return exactly one data result.");
    }
    Object raw = parts.getFirst().data();
    if (serializedSize(raw) > MAX_RESULT_BYTES) {
      throw new AgentValidationException(
          "A2A_RESULT_INVALID", "A2A task result exceeds the size limit.");
    }
    AgentResultEnvelope<JsonNode> result;
    try {
      result = objectMapper.convertValue(raw, RESULT_TYPE);
    } catch (IllegalArgumentException exception) {
      throw new AgentValidationException(
          "A2A_RESULT_INVALID", "A2A task result does not match the envelope schema.");
    }
    UUID sdkTaskId = parseTaskId(task.id());
    if (!sdkTaskId.equals(result.taskId()) || !logicalTaskId.toString().equals(task.contextId())) {
      throw new AgentValidationException(
          "A2A_TASK_ASSOCIATION_INVALID", "A2A task result does not match the delegation.");
    }
    if (result.agentName() != definition.name()) {
      throw new AgentValidationException(
          "A2A_RESULT_INVALID", "A2A task result came from an unexpected agent.");
    }
    schemaValidator.validateConstraints(result, "A2A_RESULT_INVALID");
    Object validatedResult =
        schemaValidator.parseAndValidate(
            result.result(), definition.outputType(), "A2A_RESULT_INVALID");
    return new AgentResultEnvelope<>(
        result.taskId(),
        result.agentName(),
        result.status(),
        objectMapper.valueToTree(validatedResult),
        result.evidence(),
        result.warnings(),
        result.traceId());
  }

  private int serializedSize(Object value) {
    try {
      return objectMapper.writeValueAsBytes(value).length;
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new IllegalStateException("A2A task result cannot be serialized.", exception);
    }
  }

  private AgentInput minimize(AgentInput input, AgentDefinition<?, ?> definition) {
    ContextEnvelope minimized = contextReducer.select(definition.name(), input.context());
    ObjectNode node = objectMapper.valueToTree(input);
    node.set("context", objectMapper.valueToTree(minimized));
    @SuppressWarnings("unchecked")
    Class<? extends AgentInput> inputType = (Class<? extends AgentInput>) definition.inputType();
    return schemaValidator.parseAndValidate(
        node, inputType, definition.name().name() + "_INPUT_INVALID");
  }

  private AgentResultEnvelope<JsonNode> failure(
      UUID taskId, AgentName agentName, AgentStatus status, String code, String message) {
    ObjectNode result = objectMapper.createObjectNode();
    result.put("errorCode", code);
    return new AgentResultEnvelope<>(
        taskId,
        agentName,
        status,
        result,
        List.of(),
        List.of(new AgentWarning(code, message)),
        traceContextProvider.currentOrNewTraceId());
  }

  private static Duration backoff(AgentDefinition<?, ?> definition, int attempt) {
    long initial = definition.policy().retryPolicy().initialBackoff().toMillis();
    long maximum = definition.policy().retryPolicy().maximumBackoff().toMillis();
    long multiplier = 1L << Math.min(attempt - 1, 20);
    return Duration.ofMillis(Math.min(maximum, initial * multiplier));
  }

  private static UUID executionTaskId(UUID inputTaskId, String tenantId) {
    String prefix = "planning-task:";
    if (tenantId.startsWith(prefix)) {
      return UUID.fromString(tenantId.substring(prefix.length()));
    }
    return inputTaskId;
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private static UUID parseTaskId(String taskId) {
    try {
      return UUID.fromString(taskId);
    } catch (IllegalArgumentException exception) {
      throw new AgentValidationException(
          "A2A_TASK_ASSOCIATION_INVALID", "A2A task id is not a valid UUID.");
    }
  }

  private static String errorCode(Throwable exception) {
    if (exception instanceof AgentValidationException validationException) {
      return validationException.code();
    }
    return "A2A_DELEGATION_FAILED";
  }

  private static ServerCallContext callContext(String tenantRef) {
    User user =
        new User() {
          @Override
          public boolean isAuthenticated() {
            return true;
          }

          @Override
          public String getUsername() {
            return tenantRef;
          }
        };
    return new ServerCallContext(user, Map.of("tenantRef", tenantRef), Set.of());
  }

  private static String tenantReference(String tenantId) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(tenantId.getBytes(StandardCharsets.UTF_8));
      return "tenant-" + HexFormat.of().formatHex(digest, 0, 12);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable.", exception);
    }
  }
}
