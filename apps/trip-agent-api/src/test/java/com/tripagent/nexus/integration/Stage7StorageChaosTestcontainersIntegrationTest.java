package com.tripagent.nexus.integration;

import static com.tripagent.nexus.testsupport.ChaosEvidence.duration;
import static com.tripagent.nexus.testsupport.ChaosEvidence.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.application.service.TaskApplicationService;
import com.tripagent.nexus.application.task.RedisRuntimeStateService;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import com.tripagent.nexus.testsupport.ChaosEvidence;
import com.zaxxer.hikari.HikariDataSource;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class Stage7StorageChaosTestcontainersIntegrationTest {
  static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17.11-alpine").withNetwork(NETWORK);

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379).withNetwork(NETWORK);

  @Container
  static final ToxiproxyContainer TOXI =
      new ToxiproxyContainer(
              DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0")
                  .asCompatibleSubstituteFor("shopify/toxiproxy"))
          .withNetwork(NETWORK);

  static ToxiproxyContainer.ContainerProxy postgres;
  static ToxiproxyContainer.ContainerProxy redis;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    postgres = TOXI.getProxy(POSTGRES, 5432);
    redis = TOXI.getProxy(REDIS, 6379);
    registry.add(
        "spring.datasource.url",
        () ->
            "jdbc:postgresql://"
                + postgres.getContainerIpAddress()
                + ":"
                + postgres.getProxyPort()
                + "/"
                + POSTGRES.getDatabaseName()
                + "?connectTimeout=2&socketTimeout=3");
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> value("poolSize"));
    registry.add(
        "spring.datasource.hikari.connection-timeout", () -> value("poolAcquireTimeoutMs"));
    registry.add("spring.data.redis.host", redis::getContainerIpAddress);
    registry.add("spring.data.redis.port", redis::getProxyPort);
    registry.add("spring.data.redis.password", () -> "");
    registry.add("spring.data.redis.timeout", () -> value("callTimeoutMs") + "ms");
    registry.add("spring.data.redis.connect-timeout", () -> value("callTimeoutMs") + "ms");
    registry.add("app.async-governance.redis-enabled", () -> true);
    registry.add("app.async-governance.redis-lock-ttl", () -> value("redisLockTtlMs") + "ms");
  }

  @Autowired TaskApplicationService tasks;
  @Autowired RedisRuntimeStateService runtime;
  @Autowired StringRedisTemplate template;
  @Autowired HikariDataSource pool;
  @Autowired ObjectMapper mapper;
  @Autowired AsyncGovernanceProperties properties;

  @ParameterizedTest
  @ValueSource(strings = {"latency500", "latency2000", "disconnect", "pool", "restart"})
  void postgresFaultDoesNotLoseDurableTasks(String fault) throws Exception {
    ChaosEvidence.run(
        "postgres." + fault,
        fault.equals("restart") ? "CONTAINER_RESTART" : "TOXIPROXY_OR_POOL",
        "TaskApplicationService reads through Hikari and Toxiproxy to real PostgreSQL",
        record -> {
          var task = tasks.create(TaskType.PLAN_GENERATION, UUID.randomUUID());
          tasks.transition(
              task.taskId(), TaskStatus.RUNNING, "CHAOS_DURABLE", 25, "Synthetic durable step");
          assertThat(tasks.get(task.taskId()).status()).isEqualTo(TaskStatus.RUNNING);
          var held = new ArrayList<Connection>();
          record.injected();
          try {
            switch (fault) {
              case "latency500", "latency2000" -> {
                int latency =
                    value(
                        fault.equals("latency500")
                            ? "postgresLatencySmallMs"
                            : "postgresLatencyLargeMs");
                postgres.toxics().latency("stage7-latency", ToxicDirection.DOWNSTREAM, latency);
                long started = System.nanoTime();
                try {
                  assertThat(tasks.get(task.taskId()).stage()).isEqualTo("CHAOS_DURABLE");
                } catch (org.springframework.dao.DataAccessException
                    | org.springframework.transaction.TransactionException expected) {
                  record.degraded(
                      "Task status is temporarily unavailable; durable work remains recorded.");
                }
                long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
                assertThat(elapsed).isGreaterThanOrEqualTo(latency);
                assertThat(elapsed).isLessThan(value("recoveryTimeoutMs"));
                record.measure("observedReadMs", elapsed);
              }
              case "disconnect" -> {
                postgres.setConnectionCut(true);
                assertThatThrownBy(() -> tasks.get(task.taskId()))
                    .isInstanceOf(RuntimeException.class);
                record.degraded("Task status is temporarily unavailable.");
              }
              case "pool" -> {
                for (int i = 0; i < value("poolSize"); i++) held.add(pool.getConnection());
                long started = System.nanoTime();
                assertThatThrownBy(() -> tasks.get(task.taskId()))
                    .isInstanceOf(RuntimeException.class);
                long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
                assertThat(elapsed).isGreaterThanOrEqualTo(value("poolAcquireTimeoutMs") - 20);
                assertThat(elapsed).isLessThan(value("assertionTimeoutMs"));
                record.measure("poolAcquireFailureMs", elapsed);
                record.degraded("The service is busy. Please try again shortly.");
              }
              case "restart" -> {
                POSTGRES
                    .getDockerClient()
                    .stopContainerCmd(POSTGRES.getContainerId())
                    .withTimeout(1)
                    .exec();
                assertThatThrownBy(() -> tasks.get(task.taskId()))
                    .isInstanceOf(RuntimeException.class);
                record.degraded("Task status is temporarily unavailable during recovery.");
              }
              default -> throw new IllegalArgumentException(fault);
            }
            record.detected();
          } finally {
            for (var connection : held) connection.close();
            postgres.setConnectionCut(false);
            for (var toxic : postgres.toxics().getAll()) toxic.remove();
            if (fault.equals("restart"))
              POSTGRES.getDockerClient().startContainerCmd(POSTGRES.getContainerId()).exec();
            record.cleared();
          }
          await()
              .atMost(duration("recoveryTimeoutMs"))
              .pollInterval(duration("pollIntervalMs"))
              .ignoreExceptions()
              .untilAsserted(
                  () -> assertThat(tasks.get(task.taskId()).stage()).isEqualTo("CHAOS_DURABLE"));
          await()
              .atMost(duration("recoveryTimeoutMs"))
              .untilAsserted(
                  () -> {
                    assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                    assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection())
                        .isEqualTo(value("maximumPendingConnections"));
                  });
          record.measure(
              "finalActiveConnections", pool.getHikariPoolMXBean().getActiveConnections());
          record.measure(
              "finalPendingConnections", pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
          record.tasks(task.taskId(), task.traceId(), 0, 0);
          record.recovered();
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"latency", "disconnect", "flush"})
  void redisLossKeepsDatabaseReadableAndRebuildsProjection(String fault) throws Exception {
    ChaosEvidence.run(
        "redis." + fault,
        fault.equals("flush") ? "REDIS_FLUSHDB" : "TOXIPROXY",
        "Real Redis projection of a PostgreSQL TaskRecord",
        record -> {
          var task = tasks.create(TaskType.PLAN_GENERATION, UUID.randomUUID());
          tasks.transition(
              task.taskId(), TaskStatus.RUNNING, "CACHE_DURABLE", 40, "Synthetic durable task");
          String key = "runtime:task:" + task.taskId();
          assertThat(template.opsForValue().get(key)).isNotBlank();
          record.injected();
          try {
            switch (fault) {
              case "latency" ->
                  redis
                      .toxics()
                      .latency(
                          "stage7-redis-latency",
                          ToxicDirection.DOWNSTREAM,
                          value("networkLatencyMs"));
              case "disconnect" -> redis.setConnectionCut(true);
              case "flush" -> {
                try (var connection = template.getConnectionFactory().getConnection()) {
                  connection.serverCommands().flushDb();
                }
                assertThat(template.opsForValue().get(key)).isNull();
              }
              default -> throw new IllegalArgumentException(fault);
            }
            assertThat(tasks.get(task.taskId()).status()).isEqualTo(TaskStatus.RUNNING);
            record.detected();
            record.degraded(
                "Durable task status remains readable; cache acceleration is temporarily unavailable.");
          } finally {
            redis.setConnectionCut(false);
            for (var toxic : redis.toxics().getAll()) toxic.remove();
            record.cleared();
          }
          await()
              .atMost(duration("recoveryTimeoutMs"))
              .ignoreExceptions()
              .untilAsserted(
                  () -> {
                    tasks.get(task.taskId());
                    assertThat(
                            mapper
                                .readTree(template.opsForValue().get(key))
                                .path("status")
                                .asText())
                        .isEqualTo("RUNNING");
                  });
          record.tasks(task.taskId(), task.traceId(), 0, 0);
          record.recovered();
        });
  }

  @Test
  void expiredLockOwnerCannotReleaseSuccessorsLock() throws Exception {
    ChaosEvidence.run(
        "redis.lock",
        "REAL_REDIS_TTL",
        "Token-checked release on real Redis; not lease fencing of business work",
        record -> {
          String scope = "stage7-" + UUID.randomUUID();
          var first = runtime.tryAcquireLock(scope);
          assertThat(first.acquired()).isTrue();
          record.injected();
          await().atMost(duration("recoveryTimeoutMs")).until(() -> !template.hasKey(first.key()));
          record.detected();
          var successor = runtime.tryAcquireLock(scope);
          assertThat(successor.acquired()).isTrue();
          runtime.releaseLock(first);
          assertThat(template.opsForValue().get(successor.key())).isEqualTo(successor.token());
          runtime.releaseLock(successor);
          record.cleared();
          assertThat(template.hasKey(first.key())).isFalse();
          record.recovered();
        });
  }

  @AfterAll
  static void closeNetwork() {
    NETWORK.close();
  }
}
