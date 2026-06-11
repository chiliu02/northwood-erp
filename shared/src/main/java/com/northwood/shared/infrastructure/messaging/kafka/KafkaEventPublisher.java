package com.northwood.shared.infrastructure.messaging.kafka;

import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.EventPublisher;
import com.northwood.shared.domain.Assert;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka-backed {@link EventPublisher}. Publishes an {@link EventEnvelope} as
 * JSON to topic {@code <source-service>.events}, keyed by aggregate ID so all
 * events for a given aggregate land on the same partition (preserves
 * per-aggregate ordering).
 *
 * <p>Activated by {@code @Profile("kafka")} so the default {@code dev} profile
 * never opens a broker connection. The Spring Boot 4 {@code KafkaAutoConfiguration}
 * still constructs an unused {@code KafkaTemplate} bean under {@code dev}, but
 * nothing publishes to it.
 *
 * <p>Topic derivation: {@link EventEnvelope#headers()} carries
 * {@code source-service} (set by {@link com.northwood.shared.application.outbox.OutboxDrainer}).
 * Topics are per-aggregate-context — {@code product.events}, {@code inventory.events},
 * {@code sales.events}, etc. — never per-event-type.
 */
public class KafkaEventPublisher implements EventPublisher {

    /**
     * Suffix appended to a service name to form its events topic. The single
     * source of truth for the {@code <service>.events} naming rule, so the
     * producer here and the topic declaration in
     * {@link KafkaMessagingAutoConfiguration#eventsTopic} cannot drift (a
     * mismatch would publish to a topic nobody declared).
     */
    public static final String EVENTS_TOPIC_SUFFIX = ".events";

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper json;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper json) {
        this.kafkaTemplate = kafkaTemplate;
        this.json = json;
    }

    /** The events topic a given source service publishes to — {@code <serviceName>.events}. */
    public static String topicName(String serviceName) {
        return serviceName + EVENTS_TOPIC_SUFFIX;
    }

    @Override
    public void publish(EventEnvelope envelope) {
        String sourceService = envelope.headers().get(EventEnvelope.HEADER_SOURCE_SERVICE);
        Assert.stateNotBlank(sourceService, "EventEnvelope missing '" + EventEnvelope.HEADER_SOURCE_SERVICE
                    + "' header (eventId=" + envelope.eventId() + ")");
        String topic = topicName(sourceService);
        String key = envelope.aggregateId().toString();
        String value;
        try {
            value = json.writeValueAsString(envelope);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                "Failed to serialise EventEnvelope " + envelope.eventId(), e
            );
        }
        log.debug("publishing {} ({}) to {}", envelope.eventType(), envelope.eventId(), topic);
        // Synchronous send: the OutboxDrainer.drain() transaction marks the
        // outbox row 'published' only after this returns. A broker-side failure
        // throws; OutboxDrainer catches and marks the row 'failed' with a
        // retry on the next tick.
        kafkaTemplate.send(topic, key, value).join();
    }
}
