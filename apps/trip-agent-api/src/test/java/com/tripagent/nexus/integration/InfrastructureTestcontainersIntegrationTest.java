package com.tripagent.nexus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    properties = {
      "app.async-governance.enabled=true",
      "app.async-governance.redis-enabled=true",
      "app.document-processing.messaging-enabled=false",
      "app.health.kafka.enabled=false",
      "app.health.ai-retrieval.enabled=false"
    })
@ActiveProfiles({"test", "mock-model"})
@Testcontainers
@SuppressWarnings("resource")
class InfrastructureTestcontainersIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.6-alpine"));

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4.5-alpine"))
          .withExposedPorts(6379)
          .withStartupTimeout(Duration.ofMinutes(2));

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.9.1"));

  @Container
  static final Neo4jContainer<?> NEO4J =
      new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26-community")).withoutAuthentication();

  @DynamicPropertySource
  static void infrastructureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.data.redis.password", () -> "");
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StringRedisTemplate redis;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void postgresStartsAndAppliesEveryFlywayMigration() {
    Integer migrationCount =
        jdbcTemplate.queryForObject(
            "select count(*) from flyway_schema_history where success = true", Integer.class);

    assertThat(POSTGRES.isRunning()).isTrue();
    assertThat(migrationCount).isEqualTo(11);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables "
                    + "where table_schema = 'public' and table_name in "
                    + "('trip', 'task_record', 'guarded_action', 'evaluation_run')",
                Integer.class))
        .isEqualTo(4);
  }

  @Test
  void redisPersistsRuntimeStateWithATtl() {
    String key = "phase10:testcontainers:" + UUID.randomUUID();
    redis.opsForValue().set(key, "ready", Duration.ofSeconds(30));

    assertThat(REDIS.isRunning()).isTrue();
    assertThat(redis.opsForValue().get(key)).isEqualTo("ready");
    assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isPositive();
  }

  @Test
  void kafkaPublishesAndConsumesARealBrokerRecord() throws Exception {
    String topic = "phase10.acceptance." + UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    String value = "{\"status\":\"ready\"}";
    Properties properties = new Properties();
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, "phase10-" + UUID.randomUUID());
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
      consumer.subscribe(List.of(topic));
      kafkaTemplate.send(topic, key, value).get(15, TimeUnit.SECONDS);

      var records = consumer.poll(Duration.ofSeconds(15)).records(topic).iterator();
      if (!records.hasNext()) {
        throw new AssertionError("Kafka record was not received.");
      }
      var record = records.next();
      Map<String, String> received = Map.of("key", record.key(), "value", record.value());

      assertThat(KAFKA.isRunning()).isTrue();
      assertThat(received).containsEntry("key", key).containsEntry("value", value);
    }
  }

  @Test
  void neo4jPersistsAndQueriesATravelRelationship() throws Exception {
    String statement =
        "CREATE (city:City {id: $cityId, name: $cityName})"
            + "-[:CONTAINS]->(place:Attraction {id: $placeId, name: $placeName}) "
            + "RETURN place.name AS name";
    byte[] requestBody =
        objectMapper.writeValueAsBytes(
            Map.of(
                "statements",
                List.of(
                    Map.of(
                        "statement",
                        statement,
                        "parameters",
                        Map.of(
                            "cityId",
                            "beijing",
                            "cityName",
                            "北京",
                            "placeId",
                            "forbidden-city",
                            "placeName",
                            "故宫")))));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(NEO4J.getHttpUrl() + "/db/neo4j/tx/commit"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
            .timeout(Duration.ofSeconds(15))
            .build();
    HttpResponse<byte[]> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    JsonNode result = objectMapper.readTree(response.body());

    assertThat(NEO4J.isRunning()).isTrue();
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(result.path("errors")).isEmpty();
    assertThat(result.at("/results/0/data/0/row/0").asText()).isEqualTo("故宫");
  }
}
