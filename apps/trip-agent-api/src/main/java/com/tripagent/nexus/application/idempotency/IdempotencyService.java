package com.tripagent.nexus.application.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.application.task.RedisRuntimeStateService;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.infrastructure.config.IdempotencyProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdempotencyService {

  private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final RedisRuntimeStateService runtimeState;
  private final IdempotencyProperties properties;
  private final TransactionTemplate transactionTemplate;

  public IdempotencyService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      RedisRuntimeStateService runtimeState,
      IdempotencyProperties properties,
      PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.runtimeState = runtimeState;
    this.properties = properties;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public <T> T execute(
      String key, String operation, Object request, Class<T> responseType, Supplier<T> command) {
    validateKey(key);
    String requestHash = hash(request);
    long startedAt = System.nanoTime();
    long waitTimeoutNanos = properties.getWaitTimeout().toNanos();
    Duration retryInterval = properties.getInitialRetryInterval();

    while (true) {
      Attempt<T> attempt =
          transactionTemplate.execute(
              status -> executeWithLock(key, operation, requestHash, responseType, command));
      if (attempt == null) {
        throw new IllegalStateException("Idempotency transaction returned no result");
      }
      if (attempt.lockAcquired()) {
        runtimeState.rememberIdempotency(operation, key, Duration.ofHours(24));
        return attempt.response();
      }

      long elapsedNanos = System.nanoTime() - startedAt;
      if (elapsedNanos >= waitTimeoutNanos) {
        throw requestInProgress();
      }
      sleep(minimum(retryInterval, Duration.ofNanos(waitTimeoutNanos - elapsedNanos)));
      retryInterval = minimum(retryInterval.multipliedBy(2), properties.getMaximumRetryInterval());
    }
  }

  private <T> Attempt<T> executeWithLock(
      String key,
      String operation,
      String requestHash,
      Class<T> responseType,
      Supplier<T> command) {
    Boolean lockAcquired =
        jdbcTemplate.queryForObject(
            "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))",
            Boolean.class,
            operation.length() + ":" + operation + ":" + key);
    if (!Boolean.TRUE.equals(lockAcquired)) {
      return Attempt.waiting();
    }

    List<StoredResponse> storedResponses =
        jdbcTemplate.query(
            """
            SELECT request_hash, response_body::text
            FROM idempotency_record
            WHERE operation = ? AND idempotency_key = ?
            """,
            (resultSet, rowNumber) ->
                new StoredResponse(resultSet.getString(1), resultSet.getString(2)),
            operation,
            key);
    if (!storedResponses.isEmpty()) {
      StoredResponse stored = storedResponses.getFirst();
      if (!stored.requestHash().equals(requestHash)) {
        throw new ApiException(
            "IDEMPOTENCY_KEY_REUSED",
            HttpStatus.CONFLICT,
            "The idempotency key was already used with a different request.");
      }
      return Attempt.completed(deserialize(stored.responseBody(), responseType));
    }

    T response = command.get();
    jdbcTemplate.update(
        """
            INSERT INTO idempotency_record (
                operation, idempotency_key, request_hash,
                response_type, response_body, created_at
            )
            VALUES (?, ?, ?, ?, ?::jsonb, ?)
            """,
        operation,
        key,
        requestHash,
        responseType.getName(),
        serialize(response),
        Timestamp.from(Instant.now()));
    return Attempt.completed(response);
  }

  private static ApiException requestInProgress() {
    return new ApiException(
        "IDEMPOTENCY_REQUEST_IN_PROGRESS",
        HttpStatus.CONFLICT,
        "The original request is still being processed. Retry later.");
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw requestInProgress();
    }
  }

  private static Duration minimum(Duration first, Duration second) {
    return first.compareTo(second) <= 0 ? first : second;
  }

  private static void validateKey(String key) {
    if (key == null || !KEY_PATTERN.matcher(key).matches()) {
      throw new ApiException(
          "IDEMPOTENCY_KEY_INVALID",
          HttpStatus.BAD_REQUEST,
          "Idempotency-Key must contain 16 to 128 safe characters.");
    }
  }

  private String hash(Object request) {
    byte[] serialized = serialize(request).getBytes(StandardCharsets.UTF_8);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialized));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not serialize idempotent value", exception);
    }
  }

  private <T> T deserialize(String value, Class<T> responseType) {
    try {
      return objectMapper.readValue(value, responseType);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not deserialize idempotent response", exception);
    }
  }

  private record Attempt<T>(boolean lockAcquired, T response) {

    private static <T> Attempt<T> waiting() {
      return new Attempt<>(false, null);
    }

    private static <T> Attempt<T> completed(T response) {
      return new Attempt<>(true, response);
    }
  }

  private record StoredResponse(String requestHash, String responseBody) {}
}
