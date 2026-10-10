package com.tripagent.nexus.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AgentContracts.AgentEvidence;
import com.tripagent.nexus.agent.AgentContracts.AgentInput;
import com.tripagent.nexus.agent.AgentContracts.AgentResultEnvelope;
import com.tripagent.nexus.agent.AgentContracts.AgentWarning;
import com.tripagent.nexus.agent.AgentSchemaValidator.AgentValidationException;
import com.tripagent.nexus.observability.TraceContextProvider;
import com.tripagent.nexus.skill.McpInvocationContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractA2aAgent<I extends AgentInput, O> implements TripAgent {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
  private static final Logger log = LoggerFactory.getLogger(AbstractA2aAgent.class);

  private final AgentDefinition<I, O> definition;
  private final ObjectMapper objectMapper;
  private final AgentSchemaValidator schemaValidator;
  private final TraceContextProvider traceContextProvider;
  private final Tracer tracer;

  protected AbstractA2aAgent(
      AgentDefinition<I, O> definition,
      ObjectMapper objectMapper,
      AgentSchemaValidator schemaValidator,
      TraceContextProvider traceContextProvider,
      Tracer tracer) {
    this.definition = definition;
    this.objectMapper = objectMapper;
    this.schemaValidator = schemaValidator;
    this.traceContextProvider = traceContextProvider;
    this.tracer = tracer;
  }

  @Override
  public AgentDefinition<I, O> definition() {
    return definition;
  }

  @Override
  public final void execute(RequestContext context, AgentEmitter emitter) {
    Span span =
        tracer
            .nextSpan()
            .name("a2a.agent.execute")
            .tag("agent.name", definition.name().cardName())
            .tag("a2a.task.id", context.getTaskId())
            .tag("gen_ai.prompt.version", definition.policy().promptTemplateVersion())
            .start();
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      emitter.startWork();
      I input =
          schemaValidator.parseAndValidate(
              dataPart(context.getMessage()), definition.inputType(), inputErrorCode());
      UUID ownerTaskId = ownerTaskId(context);
      AgentComputation<O> computation =
          McpInvocationContext.withOwnerTask(ownerTaskId, () -> process(input));
      span.tag("agent.status", computation.status().name());
      span.tag("agent.evidence.count", Integer.toString(computation.evidence().size()));
      span.tag(
          "operation.success",
          Boolean.toString(
              computation.status() != AgentStatus.FAILED
                  && computation.status() != AgentStatus.TIMEOUT));
      schemaValidator.validate(computation.result(), definition.outputType(), outputErrorCode());
      AgentResultEnvelope<O> envelope =
          new AgentResultEnvelope<>(
              UUID.fromString(context.getTaskId()),
              definition.name(),
              computation.status(),
              computation.result(),
              computation.evidence(),
              computation.warnings(),
              traceId(context));
      schemaValidator.validateConstraints(envelope, outputErrorCode());
      emitter.addArtifact(
          List.of(new DataPart(objectMapper.convertValue(envelope, MAP_TYPE))),
          definition.name().cardName() + "-result",
          "Schema-validated Agent result",
          Map.of("schemaVersion", "1.0.0"));
      emitter.complete();
    } catch (RuntimeException exception) {
      span.tag("operation.success", "false");
      span.tag("error.type", exception.getClass().getSimpleName());
      span.error(exception);
      log.warn(
          "Agent execution failed agent={} code={} exception={}",
          definition.name().cardName(),
          failureCode(exception),
          exception.getClass().getSimpleName());
      emitter.fail(
          emitter.newAgentMessage(
              List.of(
                  new DataPart(
                      Map.of(
                          "code",
                          failureCode(exception),
                          "agentName",
                          definition.name().cardName()))),
              Map.of("schemaVersion", "1.0.0")));
    } finally {
      span.end();
    }
  }

  @Override
  public final void cancel(RequestContext context, AgentEmitter emitter) {
    emitter.cancel();
  }

  protected abstract AgentComputation<O> process(I input);

  private static UUID ownerTaskId(RequestContext context) {
    Object value = context.getMetadata() == null ? null : context.getMetadata().get("ownerTaskId");
    if (value == null) {
      throw new AgentValidationException("A2A_EXECUTION_CONTEXT_INVALID", "Owner task is missing.");
    }
    try {
      return UUID.fromString(String.valueOf(value));
    } catch (IllegalArgumentException exception) {
      throw new AgentValidationException("A2A_EXECUTION_CONTEXT_INVALID", "Owner task is invalid.");
    }
  }

  protected record AgentComputation<T>(
      AgentStatus status, T result, List<AgentEvidence> evidence, List<AgentWarning> warnings) {

    protected AgentComputation {
      evidence = List.copyOf(evidence);
      warnings = List.copyOf(warnings);
    }

    public static <T> AgentComputation<T> succeeded(
        T result, List<AgentEvidence> evidence, List<AgentWarning> warnings) {
      return new AgentComputation<>(AgentStatus.SUCCEEDED, result, evidence, warnings);
    }

    public static <T> AgentComputation<T> degraded(
        T result, List<AgentEvidence> evidence, List<AgentWarning> warnings) {
      return new AgentComputation<>(AgentStatus.DEGRADED, result, evidence, warnings);
    }

    public static <T> AgentComputation<T> failed(
        T result, List<AgentEvidence> evidence, List<AgentWarning> warnings) {
      return new AgentComputation<>(AgentStatus.FAILED, result, evidence, warnings);
    }
  }

  private Object dataPart(Message message) {
    if (message == null) {
      throw new AgentSchemaValidator.AgentValidationException(
          inputErrorCode(), "A2A Message is required.");
    }
    List<DataPart> dataParts =
        message.parts().stream()
            .filter(DataPart.class::isInstance)
            .map(DataPart.class::cast)
            .toList();
    if (dataParts.size() != 1) {
      throw new AgentSchemaValidator.AgentValidationException(
          inputErrorCode(), "Exactly one A2A DataPart is required.");
    }
    return dataParts.getFirst().data();
  }

  private String traceId(RequestContext context) {
    Object traceId = context.getMetadata().get("traceId");
    if (traceId != null && TRACE_ID.matcher(traceId.toString()).matches()) {
      return traceId.toString();
    }
    return traceContextProvider.currentOrNewTraceId();
  }

  private String inputErrorCode() {
    return definition.name().name() + "_INPUT_INVALID";
  }

  private String outputErrorCode() {
    return definition.name().name() + "_OUTPUT_INVALID";
  }

  private String failureCode(RuntimeException exception) {
    if (exception instanceof AgentSchemaValidator.AgentValidationException validationException) {
      return validationException.code();
    }
    return definition.name().name() + "_EXECUTION_FAILED";
  }
}
