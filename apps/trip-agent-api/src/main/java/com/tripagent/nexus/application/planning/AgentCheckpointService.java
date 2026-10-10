package com.tripagent.nexus.application.planning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AgentContracts.AgentInput;
import com.tripagent.nexus.agent.AgentContracts.AgentResultEnvelope;
import com.tripagent.nexus.agent.AgentDefinition;
import com.tripagent.nexus.agent.AgentSchemaValidator;
import com.tripagent.nexus.agent.AgentStatus;
import com.tripagent.nexus.application.service.AgentExecutionService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentCheckpointService {
  private final PlanningCheckpointService planning;
  private final AgentExecutionService executions;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final AgentSchemaValidator validator;

  public AgentCheckpointService(
      PlanningCheckpointService planning,
      AgentExecutionService executions,
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      AgentSchemaValidator validator) {
    this.planning = planning;
    this.executions = executions;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.validator = validator;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Claim begin(
      UUID taskId, UUID token, AgentDefinition<?, ?> definition, AgentInput input, String tenant) {
    lock(taskId, token);
    var payload = mapper.valueToTree(input);
    if (payload.isObject())
      ((com.fasterxml.jackson.databind.node.ObjectNode) payload).remove("executionToken");
    String inputHash = planning.fingerprint(payload);
    String tenantHash = planning.fingerprint(tenant);
    String name = definition.name().cardName();
    String prompt = definition.policy().promptTemplateVersion();
    Claim cached =
        jdbc.query(
            """
        SELECT input_hash,tenant_hash,prompt_version,execution_token,agent_result_id,
        result_envelope::text FROM agent_step_checkpoint WHERE task_id=? AND agent_name=?
        """,
            row -> {
              if (!row.next()) return null;
              if (!inputHash.equals(row.getString(1))
                  || !tenantHash.equals(row.getString(2))
                  || !prompt.equals(row.getString(3))) {
                org.slf4j.LoggerFactory.getLogger(AgentCheckpointService.class)
                    .warn(
                        "Agent checkpoint rejected taskId={} agent={} inputMatches={} tenantMatches={} promptMatches={}",
                        taskId,
                        name,
                        inputHash.equals(row.getString(1)),
                        tenantHash.equals(row.getString(2)),
                        prompt.equals(row.getString(3)));
                throw PlanningCheckpointService.invalid();
              }
              if (row.getString(6) != null) {
                try {
                  AgentResultEnvelope<JsonNode> result =
                      mapper.readValue(row.getString(6), new TypeReference<>() {});
                  validate(result, definition);
                  return new Claim(row.getObject(5, UUID.class), result);
                } catch (java.io.IOException failure) {
                  throw PlanningCheckpointService.invalid();
                }
              }
              if (token.equals(row.getObject(4, UUID.class)))
                throw PlanningCheckpointService.invalid();
              executions.interruptInTransaction(row.getObject(5, UUID.class));
              return null;
            },
            taskId,
            name);
    if (cached != null) return cached;
    UUID execution = executions.startInTransaction(taskId, definition.name());
    jdbc.update(
        """
        INSERT INTO agent_step_checkpoint(task_id,agent_name,input_hash,tenant_hash,prompt_version,
          execution_token,agent_result_id) VALUES (?,?,?,?,?,?,?)
        ON CONFLICT(task_id,agent_name) DO UPDATE SET execution_token=EXCLUDED.execution_token,
          agent_result_id=EXCLUDED.agent_result_id,result_envelope=NULL
        """,
        taskId,
        name,
        inputHash,
        tenantHash,
        prompt,
        token,
        execution);
    return new Claim(execution, null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void complete(
      UUID taskId,
      UUID token,
      UUID execution,
      AgentDefinition<?, ?> definition,
      AgentResultEnvelope<JsonNode> result,
      long durationMs) {
    lock(taskId, token);
    boolean usable =
        result.status() == AgentStatus.SUCCEEDED || result.status() == AgentStatus.DEGRADED;
    if (usable) validate(result, definition);
    int updated =
        jdbc.update(
            """
        UPDATE agent_step_checkpoint SET result_envelope=?::jsonb
        WHERE task_id=? AND agent_name=? AND execution_token=? AND agent_result_id=?
          AND result_envelope IS NULL
          AND EXISTS (SELECT 1 FROM agent_result r WHERE r.agent_result_id=?
            AND r.completed_at IS NULL)
        """,
            usable ? planning.json(result) : null,
            taskId,
            definition.name().cardName(),
            token,
            execution,
            execution);
    if (updated != 1) throw PlanningCheckpointService.invalid();
    executions.completeInTransaction(execution, result, durationMs);
  }

  private void lock(UUID taskId, UUID token) {
    jdbc.query("SELECT task_id FROM task_record WHERE task_id=? FOR UPDATE", rows -> {}, taskId);
    jdbc.query(
        "SELECT task_id FROM planning_checkpoint WHERE task_id=? FOR UPDATE", rows -> {}, taskId);
    planning.requireCurrent(taskId, token);
  }

  private void validate(AgentResultEnvelope<JsonNode> result, AgentDefinition<?, ?> definition) {
    if (result.agentName() != definition.name()
        || (result.status() != AgentStatus.SUCCEEDED && result.status() != AgentStatus.DEGRADED)) {
      throw PlanningCheckpointService.invalid();
    }
    validator.validateConstraints(result, "PLANNING_CHECKPOINT_INVALID");
    validator.parseAndValidate(
        result.result(), definition.outputType(), "PLANNING_CHECKPOINT_INVALID");
  }

  public record Claim(UUID executionId, AgentResultEnvelope<JsonNode> result) {}
}
