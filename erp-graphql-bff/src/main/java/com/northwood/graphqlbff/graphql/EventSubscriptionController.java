package com.northwood.graphqlbff.graphql;

import com.northwood.graphqlbff.dto.DomainEvent;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code Subscription.events} — a live feed of every {@code *.events} message.
 * A {@code @KafkaListener} decodes each {@code EventEnvelope} (reusing
 * erp-web-ui-bff's decode shape) and publishes it into a multicast Reactor sink;
 * the subscription returns {@code sink.asFlux()}.
 *
 * <p>Active only under {@code @Profile("kafka")} with a distinct consumer
 * group-id (application-kafka.yml). Under the default {@code dev} profile this
 * bean is absent: the {@code events} field exists in the schema but never emits.
 */
@Controller
@Profile("kafka")
public class EventSubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(EventSubscriptionController.class);

    private final ObjectMapper json;
    private final Sinks.Many<DomainEvent> sink = Sinks.many().multicast().onBackpressureBuffer();

    public EventSubscriptionController(ObjectMapper json) {
        this.json = json;
    }

    @SubscriptionMapping
    public Flux<DomainEvent> events() {
        return sink.asFlux();
    }

    @KafkaListener(
        topics = "#{'${northwood.events.subscribe-topics}'.split(',')}",
        groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(ConsumerRecord<String, String> record) {
        DomainEvent event = decode(record);
        if (event != null) {
            sink.tryEmitNext(event);
        }
    }

    private DomainEvent decode(ConsumerRecord<String, String> record) {
        try {
            JsonNode env = json.readTree(record.value());
            return new DomainEvent(
                uuid(env, "eventId"),
                text(env, "eventType"),
                serviceFromTopic(record.topic()),
                text(env, "aggregateType"),
                uuid(env, "aggregateId"),
                occurredAt(env, "occurredAt")
            );
        } catch (Exception e) {
            log.debug("skipping undecodable record on {}-{}@{}: {}",
                record.topic(), record.partition(), record.offset(), e.toString());
            return null;
        }
    }

    private static String serviceFromTopic(String topic) {
        int dot = topic.indexOf('.');
        return dot < 0 ? topic : topic.substring(0, dot);
    }

    private static String text(JsonNode env, String field) {
        JsonNode node = env.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static UUID uuid(JsonNode env, String field) {
        String value = text(env, field);
        return value == null ? null : UUID.fromString(value);
    }

    private static java.time.OffsetDateTime occurredAt(JsonNode env, String field) {
        String value = text(env, field);
        // Envelope writes an ISO instant; the DateTime scalar serialises
        // OffsetDateTime, so normalise the instant to UTC.
        return value == null ? null : Instant.parse(value).atOffset(ZoneOffset.UTC);
    }
}
