package com.northwood.bff;

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
 * §2.3 events aggregator. Subscribes to every {@code *.events} Kafka topic,
 * decodes each {@code EventEnvelope}, and broadcasts a flattened DTO to all
 * SPA clients connected on {@code GET /api/events}.
 *
 * <p>Only fires under {@code @Profile("kafka")}; under the default {@code dev}
 * profile no Kafka listener is registered (the SSE endpoint still exists but
 * never emits). Mirrors the pattern in the per-service Kafka inbox setups.
 *
 * <p>The drawer rendered by the SPA is a near-real-time peek at the bus —
 * not an audit log. The consumer's {@code auto-offset-reset=latest} means
 * historical events stay on the topic; the SPA only sees what arrives after
 * connection.
 */
@RestController
@RequestMapping("/api/events")
@Profile("kafka")
public class EventsAggregatorController {

    private static final Logger log = LoggerFactory.getLogger(EventsAggregatorController.class);

    /** Wire shape sent over SSE to the SPA. Flat record so the drawer can render directly. */
    public record EventRow(
        UUID eventId,
        String eventType,
        String sourceService,    // derived from topic name (e.g. "sales" from "sales.events")
        String aggregateType,
        UUID aggregateId,
        // §1D.4: W3C trace ID extracted from EventEnvelope.headers.traceparent
        // (stamped by §1D.2's OutboxPublisher). Surfaced as a top-level field
        // so the SPA's Event Log can render a `↗ trace` affordance per row
        // that deep-links to Grafana Tempo Explore. Nullable: legacy events,
        // unit-test runs, or messages from a service that hasn't picked up
        // §1D.2 wiring yet.
        String traceId,
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
        if (subscribers.isEmpty()) {
            return;  // no SPA clients connected; drop on the floor.
        }
        EventRow row = decode(record);
        if (row == null) {
            return;
        }
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().name("event").data(row));
            } catch (Exception e) {
                log.debug("SSE subscriber dropped: {}", e.getMessage());
                subscribers.remove(emitter);
            }
        }
    }

    /**
     * Decode the {@code EventEnvelope} JSON enough to populate
     * {@link EventRow}. Doesn't deserialize the inner payload — the drawer
     * only renders the metadata. Skips malformed records with a debug log.
     */
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
                extractTraceId(env),
                getInstant(env, "occurredAt"),
                Instant.now()
            );
        } catch (Exception e) {
            log.debug("skipping undecodable record on {}-{}@{}: {}",
                record.topic(), record.partition(), record.offset(), e.toString());
            return null;
        }
    }

    /**
     * §1D.4: pull the 32-char trace ID out of the EventEnvelope's
     * {@code headers.traceparent} (W3C {@code 00-<traceId>-<spanId>-<flags>}
     * format, stamped by {@code OutboxPublisher} in §1D.2). Returns null when
     * the header is missing, malformed, or the trace ID segment isn't 32
     * hex chars — the SPA renders a disabled trace button in that case.
     */
    private static String extractTraceId(ObjectNode env) {
        JsonNode headers = env.get("headers");
        if (headers == null || !headers.isObject()) return null;
        JsonNode tp = headers.get("traceparent");
        if (tp == null || tp.isNull()) return null;
        String s = tp.asText();
        // Format: version-traceId-spanId-flags = "00-<32hex>-<16hex>-<2hex>"
        if (s.length() < 36 || s.charAt(2) != '-' || s.charAt(35) != '-') return null;
        String traceId = s.substring(3, 35);
        return traceId.length() == 32 ? traceId : null;
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
