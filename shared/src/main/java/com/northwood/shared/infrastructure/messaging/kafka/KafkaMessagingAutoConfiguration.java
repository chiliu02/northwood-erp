package com.northwood.shared.infrastructure.messaging.kafka;

import com.northwood.shared.application.messaging.InboxEnvelopeHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

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
 * <p><b>Error handling (§2.28 Tier 1.A, 2026-05-27):</b> the
 * {@link DefaultErrorHandler} bean below is auto-picked up by Spring Boot's
 * container factory and applied to every {@code @KafkaListener}. It splits
 * failures by cause instead of treating them all alike:
 *
 * <ul>
 *   <li><b>transient / infra failures</b> (DB down, broker timeout, lock
 *       contention) are retried with an {@link ExponentialBackOff} that rides
 *       out a dependency outage for up to {@code max-elapsed-time} (default
 *       5&nbsp;min) before giving up — so a multi-second blip no longer
 *       dead-letters live traffic (DR-doc <em>finding 1</em>);</li>
 *   <li><b>deterministic / poison failures</b> ({@link #NOT_RETRYABLE} — bad
 *       payload, constraint violation, unknown wire value, programming bug)
 *       skip retry entirely and route to the DLT on the first failure, since
 *       re-running can't change the outcome.</li>
 * </ul>
 *
 * <p>Either way the record lands on {@code <originalTopic>.dlt} via
 * {@link DeadLetterPublishingRecoverer} once retries are exhausted (or skipped),
 * and the source offset commits so the partition is never blocked. The DLT
 * topic is auto-created by Kafka on first publish (broker has
 * {@code auto.create.topics.enable=true} in the docker-compose); §2.28 Tier 1.B
 * adds the auto-redrive that drains it back to the source. Inbox idempotency
 * means a successful retry (or a later redrive) sees the already-processed
 * message and short-circuits via the inbox dedupe.
 */
@AutoConfiguration
@EnableKafka
public class KafkaMessagingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessagingAutoConfiguration.class);

    /**
     * Deterministic / poison exceptions that re-running cannot fix — classified
     * non-retryable so the record skips the backoff retries and routes to the
     * DLT on the first failure. Everything <em>not</em> listed here (notably the
     * {@code org.springframework.dao} transient family, broker timeouts) stays
     * retryable and is ridden out by the {@link ExponentialBackOff}.
     *
     * <ul>
     *   <li>{@link IllegalArgumentException} / {@link IllegalStateException} —
     *       the project's argument/state-check idiom
     *       ({@code com.northwood.shared.domain.Assert}); also the
     *       unknown-wire-value and serialise-defect throws in inbox handlers
     *       (e.g. {@code RawMaterialsReservedHandler},
     *       {@code ManufacturingRequestedHandler}).</li>
     *   <li>{@link DataIntegrityViolationException} — a constraint violation is a
     *       data/schema defect, not a transient hiccup (the transient
     *       {@code TransientDataAccessException} sibling is deliberately absent).</li>
     *   <li>{@link JacksonException} — malformed payload. The dispatcher already
     *       pre-catches envelope-level Jackson errors and skips them
     *       ({@code KafkaInboxDispatcher.onMessage}); this covers a nested
     *       (de)serialise defect surfacing from inside a handler.</li>
     *   <li>{@link NullPointerException} / {@link ClassCastException} — programming
     *       / type-shape bugs that won't self-heal; DLT them for inspection.</li>
     * </ul>
     */
    static final List<Class<? extends Exception>> NOT_RETRYABLE = List.of(
        IllegalArgumentException.class,
        IllegalStateException.class,
        DataIntegrityViolationException.class,
        JacksonException.class,
        NullPointerException.class,
        ClassCastException.class
    );

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
     * Per-service DLT auto-redriver (§2.28 Tier 1.B). Registered only when
     * {@code northwood.kafka.dlt.redrive.enabled=true} (set by consuming services
     * in {@code application-kafka.yml}); re-applies the records <em>this</em>
     * service dead-lettered (filtered by the {@code kafka_dlt-original-consumer-group}
     * header), bounded by {@code max-attempts}, then parks the rest. Depends on
     * {@link KafkaInboxDispatcher} for the re-apply fan-out, so it is only ever
     * enabled in services that consume (and therefore have that bean). See
     * {@link DltRedriver} for the header-routing / partition-concurrency rationale.
     */
    @Bean
    @Profile("kafka")
    @ConditionalOnProperty(prefix = "northwood.kafka.dlt.redrive", name = "enabled", havingValue = "true")
    public DltRedriver dltRedriver(
        KafkaInboxDispatcher dispatcher,
        KafkaTemplate<String, String> kafkaTemplate,
        @Value("${spring.kafka.consumer.group-id}") String ownGroup,
        @Value("${northwood.kafka.dlt.redrive.max-attempts:5}") int maxAttempts,
        @Value("${northwood.kafka.dlt.redrive.delay:10000}") long delayMs
    ) {
        return new DltRedriver(dispatcher, kafkaTemplate, ownGroup, maxAttempts, delayMs);
    }

    /**
     * Classify-and-backoff dead-letter error handler for the consumer side.
     * Spring Boot's auto-configured {@code ConcurrentKafkaListenerContainerFactory}
     * picks up the single {@link DefaultErrorHandler} bean from the context and
     * applies it to every {@code @KafkaListener} — the inbox dispatcher, and the
     * {@link DltRedriver} (on which it never fires, since that listener catches
     * its own failures and always returns normally).
     *
     * <p>The backoff is an {@link ExponentialBackOff} tuned from properties (all
     * with showcase-sane defaults), so a transient dependency outage is ridden
     * out rather than burned through in milliseconds (the prior
     * {@code FixedBackOff(0,3)} dead-lettered a multi-second blip — DR-doc
     * <em>finding 1</em>):
     *
     * <ul>
     *   <li>{@code northwood.kafka.consumer.retry.initial-interval} (default
     *       {@code 1000} ms) — delay before the first retry;</li>
     *   <li>{@code northwood.kafka.consumer.retry.multiplier} (default
     *       {@code 2.0}) — growth factor per attempt;</li>
     *   <li>{@code northwood.kafka.consumer.retry.max-interval} (default
     *       {@code 30000} ms) — cap on the inter-retry delay;</li>
     *   <li>{@code northwood.kafka.consumer.retry.max-elapsed-time} (default
     *       {@code 300000} ms = 5&nbsp;min) — total retry budget, after which the
     *       record is dead-lettered.</li>
     * </ul>
     *
     * <p>Exceptions in {@link #NOT_RETRYABLE} skip the backoff and dead-letter on
     * the first failure; see {@link #inboxErrorHandler(KafkaTemplate, BackOff)}.
     */
    @Bean
    @Profile("kafka")
    public DefaultErrorHandler kafkaErrorHandler(
        KafkaTemplate<String, String> kafkaTemplate,
        @Value("${northwood.kafka.consumer.retry.initial-interval:1000}") long initialInterval,
        @Value("${northwood.kafka.consumer.retry.multiplier:2.0}") double multiplier,
        @Value("${northwood.kafka.consumer.retry.max-interval:30000}") long maxInterval,
        @Value("${northwood.kafka.consumer.retry.max-elapsed-time:300000}") long maxElapsedTime
    ) {
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(initialInterval);
        backOff.setMultiplier(multiplier);
        backOff.setMaxInterval(maxInterval);
        backOff.setMaxElapsedTime(maxElapsedTime);
        return inboxErrorHandler(kafkaTemplate, backOff);
    }

    /**
     * Builds the consumer-side {@link DefaultErrorHandler}: the recoverer routes
     * any topic to {@code <topic>.dlt} on the original partition (logging a WARN),
     * {@link #NOT_RETRYABLE} exceptions are classified non-retryable (straight to
     * the DLT, no backoff), and everything else is retried under {@code backOff}.
     *
     * <p>Package-private + {@code BackOff}-parameterised so
     * {@code KafkaInboxDispatcherDeliveryIT} can exercise the <em>same</em>
     * classification + recoverer wiring as production with a fast backoff (the
     * test previously hand-rolled a {@code FixedBackOff(0,3)} that drifted from
     * this bean).
     */
    static DefaultErrorHandler inboxErrorHandler(
        KafkaTemplate<String, String> kafkaTemplate, BackOff backOff
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, ex) -> {
                String dlt = record.topic() + ".dlt";
                log.warn("publishing to DLT {} (partition={}, offset={}): {}",
                    dlt, record.partition(), record.offset(), ex.toString());
                return new TopicPartition(dlt, record.partition());
            }
        );
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Preserves Spring Kafka's built-in non-retryable defaults
        // (DeserializationException, MessageConversionException, …) and adds ours.
        handler.addNotRetryableExceptions(NOT_RETRYABLE.toArray(new Class[0]));
        return handler;
    }
}
