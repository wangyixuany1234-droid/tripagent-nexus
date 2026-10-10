package com.tripagent.nexus.application.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.domain.model.TaskEvent;
import com.tripagent.nexus.domain.model.TaskRecord;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RedisRuntimeStateService {

  private static final Logger log = LoggerFactory.getLogger(RedisRuntimeStateService.class);
  private static final int MAX_CACHED_SSE_EVENTS = 1000;
  private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
      new DefaultRedisScript<>(
          "if redis.call('get', KEYS[1]) == ARGV[1] then "
              + "return redis.call('del', KEYS[1]) else return 0 end",
          Long.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final AsyncGovernanceProperties properties;
  private final ConcurrentHashMap<String, LocalRateWindow> localRateWindows =
      new ConcurrentHashMap<>();

  public RedisRuntimeStateService(
      StringRedisTemplate redis, ObjectMapper objectMapper, AsyncGovernanceProperties properties) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  public void cacheTask(TaskRecord task) {
    putJson("runtime:task:" + task.taskId(), task, properties.getRedisTaskTtl());
    putJson("runtime:session:task:" + task.taskId(), task, properties.getRedisSessionTtl());
  }

  public void cacheSseEvent(TaskEvent event) {
    if (!properties.isRedisEnabled()) {
      return;
    }
    String key = "runtime:sse:" + event.taskId();
    try {
      redis.opsForList().rightPush(key, objectMapper.writeValueAsString(event));
      redis.opsForList().trim(key, -MAX_CACHED_SSE_EVENTS, -1);
      redis.expire(key, properties.getRedisSseTtl());
    } catch (DataAccessException | JsonProcessingException exception) {
      log.debug("Redis SSE cache unavailable errorType={}", exception.getClass().getSimpleName());
    }
  }

  public void cacheHotPlace(String cacheKey, JsonNode value) {
    putJson("runtime:place:" + hash(cacheKey), value, properties.getRedisHotPlaceTtl());
  }

  public Optional<JsonNode> getHotPlace(String cacheKey) {
    return getJson("runtime:place:" + hash(cacheKey));
  }

  public LockHandle tryAcquireLock(String scope) {
    if (!properties.isRedisEnabled()) {
      return new LockHandle("", "", true);
    }
    String key = "runtime:lock:" + hash(scope);
    String token = UUID.randomUUID().toString();
    try {
      Boolean acquired = redis.opsForValue().setIfAbsent(key, token, properties.getRedisLockTtl());
      return new LockHandle(key, token, Boolean.TRUE.equals(acquired));
    } catch (DataAccessException exception) {
      log.debug("Redis lock unavailable; durable database guard remains active");
      return new LockHandle("", "", true);
    }
  }

  public void releaseLock(LockHandle handle) {
    if (!properties.isRedisEnabled() || handle.key().isBlank() || !handle.acquired()) {
      return;
    }
    try {
      redis.execute(RELEASE_LOCK_SCRIPT, List.of(handle.key()), handle.token());
    } catch (DataAccessException exception) {
      log.debug("Redis lock release deferred to TTL");
    }
  }

  public boolean allow(String scope, int limit, Duration window) {
    if (!properties.isRedisEnabled()) {
      return allowLocally(scope, limit, window);
    }
    String key = "runtime:rate:" + hash(scope);
    try {
      Long count = redis.opsForValue().increment(key);
      if (count != null && count == 1L) {
        redis.expire(key, window);
      }
      return count == null || count <= limit;
    } catch (DataAccessException exception) {
      log.debug("Redis rate limiter unavailable");
      return allowLocally(scope, limit, window);
    }
  }

  private boolean allowLocally(String scope, int limit, Duration window) {
    long now = System.nanoTime();
    long windowNanos = window.toNanos();
    AtomicBoolean allowed = new AtomicBoolean();
    localRateWindows.compute(
        hash(scope),
        (key, previous) -> {
          LocalRateWindow next =
              previous == null || now - previous.startedNanos() >= windowNanos
                  ? new LocalRateWindow(now, 1)
                  : new LocalRateWindow(previous.startedNanos(), previous.count() + 1);
          allowed.set(next.count() <= limit);
          return next;
        });
    return allowed.get();
  }

  public void rememberIdempotency(String operation, String key, Duration ttl) {
    if (!properties.isRedisEnabled()) {
      return;
    }
    try {
      redis.opsForValue().set("runtime:idempotency:" + hash(operation + ":" + key), "1", ttl);
    } catch (DataAccessException exception) {
      log.debug("Redis idempotency cache unavailable");
    }
  }

  private void putJson(String key, Object value, Duration ttl) {
    if (!properties.isRedisEnabled()) {
      return;
    }
    try {
      redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
    } catch (DataAccessException | JsonProcessingException exception) {
      log.debug(
          "Redis runtime cache unavailable errorType={}", exception.getClass().getSimpleName());
    }
  }

  private Optional<JsonNode> getJson(String key) {
    if (!properties.isRedisEnabled()) {
      return Optional.empty();
    }
    try {
      String value = redis.opsForValue().get(key);
      return value == null ? Optional.empty() : Optional.of(objectMapper.readTree(value));
    } catch (DataAccessException | JsonProcessingException exception) {
      return Optional.empty();
    }
  }

  private static String hash(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 16);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable.", exception);
    }
  }

  public record LockHandle(String key, String token, boolean acquired) {}

  private record LocalRateWindow(long startedNanos, int count) {}
}
