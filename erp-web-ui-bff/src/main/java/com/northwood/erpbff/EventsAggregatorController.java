package com.northwood.erpbff;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Subscribes to every {@code *.events} Kafka topic, decodes each
 * {@code EventEnvelope}, and broadcasts a flattened DTO to all SPA clients
 * connected on {@code GET /api/events}. Powers the ERP UI's notification
 * bell — operators see live activity across every service.
 *
 * <p>Only fires under {@code @Profile("kafka")}. Under the default
 * {@code dev} profile no Kafka listener is registered (the SSE endpoint
 * still exists but never emits).
 */
@RestController
@RequestMapping("/api/events")
@Profile("kafka")
public class EventsAggregatorController {

    private static final Logger log = LoggerFactory.getLogger(EventsAggregatorController.class);

    public record EventRow(
        UUID eventId,
        String eventType,
        String sourceService,
        String aggregateType,
        UUID aggregateId,
        Instant occurredAt,
        Instant receivedAt
    ) {}

    private final ObjectMapper json;
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    public EventsAggregatorController(ObjectMapper json) {
        this.json = json;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception ignored) {
            subscribers.remove(emitter);
        }
        return emitter;
    }

    @KafkaListener(
        topics = "#{'${northwood.events.subscribe-topics}'.split(',')}",
        groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(ConsumerRecord<String, String> record) {
        if (subscribers.isEmpty()) return;
        EventRow row = decode(record);
        if (row == null) return;
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().name("event").data(row));
            } catch (Exception e) {
                log.debug("SSE subscriber dropped: {}", e.getMessage());
                subscribers.remove(emitter);
            }
        }
    }

    private EventRow decode(ConsumerRecord<String, String> record) {
        try {
            ObjectNode env = (ObjectNode) json.readTree(record.value());
            String sourceService = topicToService(record.topic());
            return new EventRow(
                getUuid(env, "eventId"),
                getString(env, "eventType"),
                sourceService,
                getString(env, "aggregateType"),
                getUuid(env, "aggregateId"),
                getInstant(env, "occurredAt"),
                Instant.now()
            );
        } catch (Exception e) {
            log.debug("skipping undecodable record on {}-{}@{}: {}",
                record.topic(), record.partition(), record.offset(), e.toString());
            return null;
        }
    }

    private static String topicToService(String topic) {
        int dot = topic.indexOf('.');
        return dot < 0 ? topic : topic.substring(0, dot);
    }

    private static String getString(JsonNode env, String field) {
        JsonNode n = env.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static UUID getUuid(JsonNode env, String field) {
        String s = getString(env, field);
        return s == null ? null : UUID.fromString(s);
    }

    private static Instant getInstant(JsonNode env, String field) {
        String s = getString(env, field);
        return s == null ? null : Instant.parse(s);
    }
}
