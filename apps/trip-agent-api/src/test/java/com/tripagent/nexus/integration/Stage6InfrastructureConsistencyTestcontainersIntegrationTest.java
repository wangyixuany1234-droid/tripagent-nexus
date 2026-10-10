package com.tripagent.nexus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class Stage6InfrastructureConsistencyTestcontainersIntegrationTest {

  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine"))
          .withNetwork(NETWORK)
          .withNetworkAliases("stage6-postgres");

  @Container
  static final ToxiproxyContainer TOXIPROXY =
      new ToxiproxyContainer(
              DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0")
                  .asCompatibleSubstituteFor("shopify/toxiproxy"))
          .withNetwork(NETWORK);

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.9.1"));

  @Test
  void postgresDisconnectRollsBackAndDataRemainsReadableAfterRecovery() throws Exception {
    ToxiproxyContainer.ContainerProxy proxy = TOXIPROXY.getProxy(POSTGRES, 5432);
    String jdbcUrl =
        "jdbc:postgresql://"
            + proxy.getContainerIpAddress()
            + ":"
            + proxy.getProxyPort()
            + "/"
            + POSTGRES.getDatabaseName()
            + "?connectTimeout=2&socketTimeout=1";
    try (var connection =
        DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      connection
          .createStatement()
          .execute("CREATE TABLE stage6_probe (id UUID PRIMARY KEY, value VARCHAR(32) NOT NULL)");
      connection
          .createStatement()
          .executeUpdate(
              "INSERT INTO stage6_probe VALUES ('00000000-0000-0000-0000-000000000001', 'before')");
    }

    try (var interrupted =
        DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      interrupted.setAutoCommit(false);
      var timeout =
          proxy.toxics().timeout("stage6-postgres-timeout", ToxicDirection.DOWNSTREAM, 100);
      try {
        assertThatThrownBy(
                () ->
                    interrupted
                        .createStatement()
                        .executeUpdate(
                            "INSERT INTO stage6_probe VALUES "
                                + "('00000000-0000-0000-0000-000000000002', 'during')"))
            .isInstanceOf(Exception.class);
      } finally {
        timeout.remove();
      }
    }

    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    Integer count = null;
    while (count == null && System.nanoTime() < deadline) {
      try (var connection =
          DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
        var result = connection.createStatement().executeQuery("SELECT count(*) FROM stage6_probe");
        result.next();
        count = result.getInt(1);
      } catch (Exception ignored) {
        LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
      }
    }
    assertThat(count).isEqualTo(1);
  }

  @Test
  void kafkaBrokerDisconnectFailsFastAndRecoversWithoutChangingTheEventId() throws Exception {
    Properties properties = producerProperties();
    String topic = "stage6.kafka.recovery." + UUID.randomUUID();
    String eventId = UUID.randomUUID().toString();
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
      producer
          .send(new ProducerRecord<>(topic, eventId, "{\"status\":\"before\"}"))
          .get(10, TimeUnit.SECONDS);

      KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
      try {
        assertThatThrownBy(
                () ->
                    producer
                        .send(new ProducerRecord<>(topic, eventId, "{\"status\":\"during\"}"))
                        .get(5, TimeUnit.SECONDS))
            .isInstanceOf(Exception.class);
      } finally {
        KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
      }
    }

    try (KafkaProducer<String, String> recovered = new KafkaProducer<>(properties)) {
      var metadata =
          recovered
              .send(new ProducerRecord<>(topic, eventId, "{\"status\":\"after\"}"))
              .get(15, TimeUnit.SECONDS);
      assertThat(metadata.topic()).isEqualTo(topic);
    }
  }

  private static Properties producerProperties() {
    Properties properties = new Properties();
    properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    properties.put(ProducerConfig.ACKS_CONFIG, "all");
    properties.put(ProducerConfig.RETRIES_CONFIG, "0");
    properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "1000");
    properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "2000");
    properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "2000");
    return properties;
  }

  @AfterAll
  static void closeNetwork() {
    NETWORK.close();
  }
}
