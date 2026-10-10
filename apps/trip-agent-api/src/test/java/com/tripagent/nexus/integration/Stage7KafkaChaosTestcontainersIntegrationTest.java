package com.tripagent.nexus.integration;

import static com.tripagent.nexus.testsupport.ChaosEvidence.duration;
import static com.tripagent.nexus.testsupport.ChaosEvidence.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tripagent.nexus.testsupport.ChaosEvidence;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class Stage7KafkaChaosTestcontainersIntegrationTest {
  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka-native:3.9.1")
          .withStartupTimeout(duration("containerStartupTimeoutMs"));

  @ParameterizedTest
  @ValueSource(strings = {"unavailable", "latency", "duplicate", "pause", "backlog"})
  void brokerAndConsumerRecoverWithMeasuredCommittedLag(String fault) throws Exception {
    ChaosEvidence.run(
        "kafka." + fault,
        "CONTAINER_AND_KAFKA_CLIENT_CONTROL",
        "Real Kafka broker and synthetic records; not the business planning listener",
        record -> {
          String topic = "stage7-" + UUID.randomUUID();
          var partition = new TopicPartition(topic, 0);
          Properties config = config();
          try (var producer = new KafkaProducer<String, String>(config);
              var consumer = new KafkaConsumer<String, String>(config);
              var worker = Executors.newSingleThreadExecutor()) {
            producer
                .send(new ProducerRecord<>(topic, "baseline", "synthetic"))
                .get(value("recoveryTimeoutMs"), TimeUnit.MILLISECONDS);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            drain(consumer, 1);
            consumer.commitSync(duration("recoveryTimeoutMs"));
            var expected = new HashSet<String>();
            int sent;
            record.injected();
            if (fault.equals("unavailable")) {
              KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
              try {
                assertThatThrownBy(
                        () ->
                            producer
                                .send(new ProducerRecord<>(topic, "uncertain", "synthetic"))
                                .get(
                                    value("kafkaDeliveryTimeoutMs") + value("assertionTimeoutMs"),
                                    TimeUnit.MILLISECONDS))
                    .isInstanceOf(Exception.class);
                record.degraded(
                    "Message publication is temporarily unavailable; delivery outcome is uncertain.");
              } finally {
                KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
                record.cleared();
              }
              producer
                  .send(new ProducerRecord<>(topic, "recovered", "synthetic"))
                  .get(value("recoveryTimeoutMs"), TimeUnit.MILLISECONDS);
              expected.add("recovered");
              sent = 1;
            } else if (fault.equals("latency")) {
              KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
              try {
                var delayed =
                    worker.submit(
                        () ->
                            producer
                                .send(new ProducerRecord<>(topic, "delayed", "synthetic"))
                                .get(value("recoveryTimeoutMs"), TimeUnit.MILLISECONDS));
                long started = System.nanoTime();
                Thread.sleep(duration("networkLatencyMs"));
                assertThat(delayed.isDone()).isFalse();
                record.detected();
                KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
                record.cleared();
                delayed.get(value("recoveryTimeoutMs"), TimeUnit.MILLISECONDS);
                record.measure(
                    "deliveryMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
              } finally {
                if (Boolean.TRUE.equals(
                    KAFKA
                        .getDockerClient()
                        .inspectContainerCmd(KAFKA.getContainerId())
                        .exec()
                        .getState()
                        .getPaused())) {
                  KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
                }
              }
              expected.add("delayed");
              sent = 1;
            } else {
              consumer.pause(List.of(partition));
              int count = fault.equals("backlog") ? value("kafkaBacklogRecords") : 2;
              var futures = new ArrayList<java.util.concurrent.Future<?>>();
              for (int i = 0; i < count; i++) {
                String key = fault.equals("duplicate") ? "same-event" : "event-" + i;
                expected.add(key);
                futures.add(producer.send(new ProducerRecord<>(topic, key, "synthetic")));
              }
              for (var future : futures)
                future.get(value("recoveryTimeoutMs"), TimeUnit.MILLISECONDS);
              long lag = lag(consumer, partition);
              assertThat(lag).isEqualTo(count);
              assertThat(consumer.poll(duration("callTimeoutMs"))).isEmpty();
              record.detected();
              record.measure("peakCommittedLag", lag);
              record.degraded("Synthetic messages are queued while the test consumer is paused.");
              consumer.resume(List.of(partition));
              record.cleared();
              sent = count;
            }
            var received = new ArrayList<String>();
            long deadline = System.nanoTime() + duration("recoveryTimeoutMs").toNanos();
            while ((!received.containsAll(expected) || lag(consumer, partition) != 0)
                && System.nanoTime() < deadline) {
              for (var item : consumer.poll(duration("callTimeoutMs"))) received.add(item.key());
              consumer.commitSync(duration("recoveryTimeoutMs"));
            }
            assertThat(received).containsAll(expected);
            if (!fault.equals("unavailable")) assertThat(received).hasSize(sent);
            assertThat(lag(consumer, partition)).isEqualTo(value("maximumFinalKafkaLag"));
            if (fault.equals("duplicate"))
              assertThat(received).containsExactly("same-event", "same-event");
            record.measure("receivedRecords", received.size());
            record.measure("receivedUniqueKeys", new HashSet<>(received).size());
            record.measure("finalCommittedLag", lag(consumer, partition));
            record.detected();
            record.recovered();
          }
        });
  }

  private static long lag(KafkaConsumer<String, String> consumer, TopicPartition partition) {
    return consumer.endOffsets(List.of(partition), duration("recoveryTimeoutMs")).get(partition)
        - consumer
            .committed(java.util.Set.of(partition), duration("recoveryTimeoutMs"))
            .get(partition)
            .offset();
  }

  private static void drain(KafkaConsumer<String, String> consumer, int count) {
    int received = 0;
    long deadline = System.nanoTime() + duration("recoveryTimeoutMs").toNanos();
    while (received < count && System.nanoTime() < deadline)
      received += consumer.poll(duration("callTimeoutMs")).count();
    assertThat(received).isEqualTo(count);
  }

  private static Properties config() {
    Properties result = new Properties();
    result.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    result.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    result.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    result.put(ProducerConfig.ACKS_CONFIG, "all");
    result.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, value("kafkaRequestTimeoutMs"));
    result.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, value("kafkaDeliveryTimeoutMs"));
    result.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, value("recoveryTimeoutMs"));
    result.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    result.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    result.put(ConsumerConfig.GROUP_ID_CONFIG, "stage7-" + UUID.randomUUID());
    result.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    return result;
  }
}
