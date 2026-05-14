package com.northwood.shared.infrastructure.messaging.kafka;

import com.northwood.shared.application.messaging.InboxEnvelopeHandler;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Wires the Kafka producer and consumer template across the
 * the shared module module boundary so each service does not have
 * to repeat the @Component declarations under its own component-scan root.
 *
 * <p>Active only under {@code @Profile("kafka")}. Under the default
 * {@code dev} profile this class contributes nothing — Spring Boot's
 * {@code KafkaAutoConfiguration} still wires a {@link KafkaTemplate} bean from
 * the {@code spring.kafka.*} properties (or no-op defaults) but no publisher
 * or dispatcher is registered, so no broker connection is attempted.
 *
 * <p>The {@link KafkaInboxDispatcher} bean is additionally guarded on
 * {@code northwood.kafka.subscribe-topics}: producer-only services
 * (product-service today) do not register a {@code @KafkaListener} and stay
 * silent on the consumer side.
 *
 * <p><b>Error handling (§2.2, 2026-05-06):</b> the {@link DefaultErrorHandler}
 * bean below is auto-picked up by Spring Boot's container factory. It retries
 * a failed message 3 times in-process (immediate retry, no backoff) and then
 * routes to a dead-letter topic {@code <originalTopic>.dlt} via
 * {@link DeadLetterPublishingRecoverer}. The DLT topic is auto-created by
 * Kafka on first publish (broker has {@code auto.create.topics.enable=true}
 * in the docker-compose). Inbox idempotency means a successful retry sees the
 * already-processed message and short-circuits via the inbox dedupe.
 */
@AutoConfiguration
@EnableKafka
public class KafkaMessagingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessagingAutoConfiguration.class);

    @Bean
    @Profile("kafka")
    public KafkaEventPublisher kafkaEventPublisher(
        KafkaTemplate<String, String> kafkaTemplate,
        ObjectMapper json
    ) {
        return new KafkaEventPublisher(kafkaTemplate, json);
    }

    @Bean
    @Profile("kafka")
    @ConditionalOnProperty(prefix = "northwood.kafka", name = "subscribe-topics")
    public KafkaInboxDispatcher kafkaInboxDispatcher(
        List<InboxEnvelopeHandler> handlers,
        ObjectMapper json
    ) {
        return new KafkaInboxDispatcher(handlers, json);
    }

    /**
     * Bounded-retry + dead-letter error handler for the consumer side. Spring
     * Boot's auto-configured {@code ConcurrentKafkaListenerContainerFactory}
     * picks up the single {@link DefaultErrorHandler} bean from the context
     * and applies it to every {@code @KafkaListener} (the inbox dispatcher is
     * the only one today). The recoverer's destination resolver maps any
     * topic to {@code <topic>.dlt} keeping the original partition.
     *
     * <p>Retry policy is intentionally simple for the showcase: 3 immediate
     * in-process retries, no backoff, no exponential growth. Most failures
     * here are transient (db lock contention, transient outbound calls); the
     * inbox idempotency guard handles the at-least-once delivery cleanly.
     * Persistent failures (poison pills, bad payload shape, NPE bug) flow to
     * the DLT after the third attempt for human inspection.
     */
    @Bean
    @Profile("kafka")
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, ex) -> {
                String dlt = record.topic() + ".dlt";
                log.warn("publishing to DLT {} (partition={}, offset={}): {}",
                    dlt, record.partition(), record.offset(), ex.toString());
                return new TopicPartition(dlt, record.partition());
            }
        );
        // FixedBackOff(intervalMillis, maxAttempts): 3 retries with no delay.
        return new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 3));
    }
}
