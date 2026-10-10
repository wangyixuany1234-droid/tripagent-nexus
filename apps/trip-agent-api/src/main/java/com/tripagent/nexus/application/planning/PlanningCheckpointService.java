package com.tripagent.nexus.application.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripagent.nexus.agent.coordinator.CoordinatorAgentModels.Input;
import com.tripagent.nexus.infrastructure.config.PlanningProperties;
import com.tripagent.nexus.integration.messaging.AsyncOperationException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanningCheckpointService {
  private final DataSource source;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final PlanningProperties properties;
  private final Clock clock;
  private final java.util.concurrent.Semaphore executionCapacity;

  public PlanningCheckpointService(
      DataSource source,
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      PlanningProperties properties,
      Clock clock) {
    this.source = source;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.properties = properties;
    this.clock = clock;
    int limit = properties.getMaxConcurrentWorkflows();
    try {
      if (source.isWrapperFor(com.zaxxer.hikari.HikariDataSource.class)) {
        int poolSize = source.unwrap(com.zaxxer.hikari.HikariDataSource.class).getMaximumPoolSize();
        if (poolSize < 2) throw new IllegalStateException("PLANNING_LOCK_CAPACITY_INVALID");
        // Advisory locks retain a connection; leave capacity for short business transactions.
        limit = Math.min(limit, poolSize / 2);
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("PLANNING_LOCK_CAPACITY_INVALID", failure);
    }
    executionCapacity = new java.util.concurrent.Semaphore(limit);
  }

  public Optional<ExecutionLock> tryLock(UUID taskId) {
    if (!executionCapacity.tryAcquire()) {
      throw new AsyncOperationException(
          "PLANNING_CAPACITY_BUSY", true, "Planning is busy. The request can be retried.");
    }
    Connection connection = null;
    try {
      connection = source.getConnection();
      try (var statement =
          connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 7))")) {
        statement.setString(1, taskId.toString());
        try (var result = statement.executeQuery()) {
          result.next();
          if (result.getBoolean(1))
            return Optional.of(new ExecutionLock(connection, taskId, executionCapacity));
        }
      }
      connection.close();
      executionCapacity.release();
      return Optional.empty();
    } catch (SQLException failure) {
      if (connection != null)
        try {
          connection.close();
        } catch (SQLException ignored) {
        }
      executionCapacity.release();
      throw invalid();
    }
  }

  @Transactional
  public Snapshot create(
      UUID taskId,
      String inputHash,
      String tenant,
      Input input,
      PlanningKnowledgeService.KnowledgeResult knowledge) {
    UUID token = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO planning_checkpoint(task_id,input_hash,tenant_hash,coordinator_input,knowledge,
        execution_token,created_at,expires_at) VALUES (?,?,?,?::jsonb,?::jsonb,?,?,?)
        """,
        taskId,
        inputHash,
        fingerprint(tenant),
        json(input),
        json(knowledge),
        token,
        Timestamp.from(clock.instant()),
        Timestamp.from(clock.instant().plus(properties.getCheckpointRetention())));
    return new Snapshot(new Input(taskId, input.context(), input.requestText(), token), knowledge);
  }

  @Transactional
  public Snapshot resume(UUID taskId, String inputHash, String tenant) {
    jdbc.query("SELECT task_id FROM task_record WHERE task_id=? FOR UPDATE", rows -> {}, taskId);
    return jdbc.query(
        """
        SELECT c.input_hash,c.tenant_hash,c.coordinator_input::text,c.knowledge::text,c.expires_at
        FROM planning_checkpoint c JOIN task_record t USING(task_id)
        WHERE c.task_id=? AND t.status='RUNNING' AND t.stage='AGENTS_RUNNING'
          AND NOT t.cancel_requested FOR UPDATE OF c
        """,
        result -> {
          if (!result.next()
              || !result.getString(1).equals(inputHash)
              || !result.getString(2).equals(fingerprint(tenant))
              || !result.getTimestamp(5).toInstant().isAfter(clock.instant())) throw invalid();
          try {
            Input input = mapper.readValue(result.getString(3), Input.class);
            var knowledge =
                mapper.readValue(
                    result.getString(4), PlanningKnowledgeService.KnowledgeResult.class);
            UUID token = UUID.randomUUID();
            jdbc.update(
                "UPDATE planning_checkpoint SET execution_token=? WHERE task_id=?", token, taskId);
            return new Snapshot(
                new Input(taskId, input.context(), input.requestText(), token), knowledge);
          } catch (java.io.IOException failure) {
            throw invalid();
          }
        },
        taskId);
  }

  public boolean resumable(UUID taskId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS (SELECT 1 FROM planning_checkpoint c JOIN task_record t USING(task_id)
          JOIN travel_plan p ON p.plan_id=t.resource_id
          WHERE c.task_id=? AND c.expires_at > ? AND t.status='RUNNING'
          AND t.stage='AGENTS_RUNNING' AND t.progress=40 AND NOT t.cancel_requested
          AND t.task_type IN ('PLAN_GENERATION','PLAN_REPLAN')
          AND t.result_resource_ids='[]'::jsonb AND p.status='GENERATING')
        """,
            Boolean.class,
            taskId,
            Timestamp.from(clock.instant())));
  }

  public void requireCurrent(UUID taskId, UUID token) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS (SELECT 1 FROM planning_checkpoint c JOIN task_record t USING(task_id)
          WHERE c.task_id=? AND c.execution_token=? AND c.expires_at > ?
          AND t.task_type IN ('PLAN_GENERATION','PLAN_REPLAN')
          AND t.status='RUNNING' AND t.stage='AGENTS_RUNNING' AND NOT t.cancel_requested)
        """,
            Boolean.class,
            taskId,
            token,
            Timestamp.from(clock.instant())))) throw invalid();
  }

  public void remove(UUID taskId) {
    jdbc.update("DELETE FROM planning_checkpoint WHERE task_id=?", taskId);
  }

  public void completeRecoveredInbox(UUID taskId) {
    jdbc.update(
        """
        UPDATE event_inbox SET status='COMPLETED',completed_at=?,updated_at=?
        WHERE task_id=? AND consumer_name='plan-orchestrator-v1' AND status='PROCESSING'
        AND EXISTS (SELECT 1 FROM task_record t WHERE t.task_id=?
          AND t.status IN ('SUCCEEDED','PARTIAL_SUCCESS','CANCELLED'))
        """,
        Timestamp.from(clock.instant()),
        Timestamp.from(clock.instant()),
        taskId,
        taskId);
  }

  @Scheduled(fixedDelayString = "${app.planning.checkpoint-cleanup-interval:1h}")
  public void purgeExpired() {
    jdbc.update(
        "DELETE FROM planning_checkpoint WHERE expires_at <= ?", Timestamp.from(clock.instant()));
  }

  public String fingerprint(Object value) {
    try {
      JsonNode node = canonical(mapper.valueToTree(value));
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(node)));
    } catch (java.security.NoSuchAlgorithmException | java.io.IOException failure) {
      throw new IllegalStateException("CHECKPOINT_HASH_FAILED", failure);
    }
  }

  private JsonNode canonical(JsonNode node) {
    if (node.isNumber()) {
      return com.fasterxml.jackson.databind.node.DecimalNode.valueOf(
          node.decimalValue().stripTrailingZeros());
    }
    if (node.isObject()) {
      ObjectNode result = mapper.createObjectNode();
      var fields = new java.util.TreeSet<String>();
      node.fieldNames().forEachRemaining(fields::add);
      fields.forEach(key -> result.set(key, canonical(node.get(key))));
      return result;
    }
    if (node.isArray()) {
      var result = mapper.createArrayNode();
      node.forEach(item -> result.add(canonical(item)));
      return result;
    }
    return node;
  }

  public String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (java.io.IOException failure) {
      throw invalid();
    }
  }

  public static AsyncOperationException invalid() {
    return new AsyncOperationException(
        "PLANNING_CHECKPOINT_INVALID",
        false,
        "The saved planning step cannot be safely resumed. Please start a new plan.");
  }

  public record Snapshot(Input input, PlanningKnowledgeService.KnowledgeResult knowledge) {}

  public static final class ExecutionLock implements AutoCloseable {
    private final Connection connection;
    private final UUID taskId;
    private final java.util.concurrent.Semaphore capacity;
    private final java.util.concurrent.atomic.AtomicBoolean closed =
        new java.util.concurrent.atomic.AtomicBoolean();

    ExecutionLock(Connection connection, UUID taskId, java.util.concurrent.Semaphore capacity) {
      this.connection = connection;
      this.taskId = taskId;
      this.capacity = capacity;
    }

    public void requireValid() {
      if (!isValid()) throw invalid();
    }

    public boolean isValid() {
      try {
        return !closed.get() && connection.isValid(1);
      } catch (SQLException failure) {
        return false;
      }
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) return;
      try (connection) {
        try (var statement =
            connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 7))")) {
          statement.setString(1, taskId.toString());
          statement.execute();
        }
      } catch (SQLException ignored) {
        // A lost session has already released its advisory locks.
      } finally {
        capacity.release();
      }
    }
  }
}
