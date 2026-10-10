package com.tripagent.nexus.integration.messaging;

import com.tripagent.nexus.application.service.DeadLetterService;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(
    name = "app.async-governance.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class KafkaGovernanceConfiguration {

  @Bean
  KafkaAdmin.NewTopics governedTopics(AsyncGovernanceProperties properties) {
    return new KafkaAdmin.NewTopics(
        topic(properties.getPlanRequestedTopic(), 24),
        topic(properties.getDocumentParseRequestedTopic(), 12),
        topic(properties.getDocumentParseCompletedTopic(), 12),
        topic(properties.getRetrievalRequestedTopic(), 24),
        topic(properties.getAgentTaskRequestedTopic(), 48),
        topic(properties.getAgentTaskCompletedTopic(), 48),
        topic(properties.getPlanReviewRequestedTopic(), 24),
        topic(properties.getPlanCompletedTopic(), 24),
        topic(properties.getTaskFailedTopic(), 24),
        topic(properties.getDeadLetterTopic(), 12));
  }

  @Bean
  DefaultErrorHandler governedKafkaErrorHandler(
      DeadLetterService deadLetterService, AsyncGovernanceProperties properties) {
    DefaultErrorHandler handler =
        new DefaultErrorHandler(
            (record, exception) -> deadLetterService.capture(record, exception),
            retryBackoff(properties));
    handler.setBackOffFunction(
        (record, exception) -> {
          AsyncOperationException failure = findAsyncFailure(exception);
          return failure != null && !failure.retryable()
              ? new FixedBackOff(0, 0)
              : retryBackoff(properties);
        });
    handler.setCommitRecovered(true);
    return handler;
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory,
      DefaultErrorHandler governedKafkaErrorHandler) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.setCommonErrorHandler(governedKafkaErrorHandler);
    factory.getContainerProperties().setObservationEnabled(true);
    return factory;
  }

  private static NewTopic topic(String name, int partitions) {
    return TopicBuilder.name(name).partitions(partitions).build();
  }

  private static ExponentialBackOffWithMaxRetries retryBackoff(
      AsyncGovernanceProperties properties) {
    ExponentialBackOffWithMaxRetries backoff =
        new ExponentialBackOffWithMaxRetries(Math.max(0, properties.getMaxAttempts() - 1));
    backoff.setInitialInterval(properties.getInitialBackoff().toMillis());
    backoff.setMultiplier(2.0);
    backoff.setMaxInterval(properties.getMaximumBackoff().toMillis());
    return backoff;
  }

  private static AsyncOperationException findAsyncFailure(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof AsyncOperationException asyncFailure) {
        return asyncFailure;
      }
      current = current.getCause();
    }
    return null;
  }
}
