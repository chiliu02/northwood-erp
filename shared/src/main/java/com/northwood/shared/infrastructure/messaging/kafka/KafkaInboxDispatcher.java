package com.northwood.shared.infrastructure.messaging.kafka;

import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.InboxEnvelopeHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Kafka-side consumer template. One {@code @KafkaListener} subscribes to all
 * topics the service cares about (configured via
 * {@code northwood.kafka.subscribe-topics}), deserialises each record's value
 * back into an {@link EventEnvelope}, and fans out to every Spring bean
 * implementing {@link InboxEnvelopeHandler} whose
 * {@link InboxEnvelopeHandler#handles(String) handles} returns true for the
 * event type.
 *
 * <p>Activated by {@code @Profile("kafka")}. Additionally guarded on
 * {@code northwood.kafka.subscribe-topics} being set, so producer-only services
 * (e.g. product-service today) do not spin up a consumer they have nothing to
 * subscribe to.
 *
 * <p>Idempotency is the handler's responsibility (via
 * {@link com.northwood.shared.application.inbox.InboxPort}). A handler
 * exception aborts ack so the message is redelivered by the broker on the next
 * poll — combined with the inbox table the effect is at-least-once with
 * exactly-once application.
 */
public class KafkaInboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(KafkaInboxDispatcher.class);

    private final List<InboxEnvelopeHandler> handlers;
    private final ObjectMapper json;

    public KafkaInboxDispatcher(List<InboxEnvelopeHandler> handlers, ObjectMapper json) {
        this.handlers = handlers;
        this.json = json;
        log.info(
            "KafkaInboxDispatcher wired with {} handler(s): {}",
            handlers.size(),
            handlers.stream().map(h -> h.consumerName()).toList()
        );
    }

    @KafkaListener(
        topics = "#{'${northwood.kafka.subscribe-topics}'.split(',')}",
        groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(ConsumerRecord<String, String> record) {
        // Offset-commit contract (Spring Kafka defaults — enable.auto.commit=false
        // + AckMode.BATCH; see docs/messaging.md → Consumer-side idempotency):
        // the container commits this record's offset ONLY after this method
        // returns normally. So returning normally (a successful handle, or the
        // malformed-skip below) commits the offset; letting an exception
        // propagate aborts the commit and the DefaultErrorHandler re-seeks, so
        // the record is redelivered (then dead-lettered after the retry budget).
        // At-least-once delivery + the inbox dedup inside each handler =
        // exactly-once effect. Covered by KafkaInboxDispatcherDeliveryIT.
        EventEnvelope envelope;
        try {
            envelope = json.readValue(record.value(), EventEnvelope.class);
        } catch (JacksonException e) {
            // Malformed envelope on the topic — log and return normally, which
            // commits (skips) the offset rather than blocking the partition on a
            // poison message. This is the one failure path that does NOT
            // redeliver. (A dedicated DLQ for malformed payloads is deferred in
            // dev-todo.md.)
            log.error(
                "Skipping malformed envelope on {}-{}@{}: {}",
                record.topic(), record.partition(), record.offset(), e.getMessage()
            );
            return;
        }

        boolean dispatched = false;
        for (InboxEnvelopeHandler handler : handlers) {
            if (!handler.handles(envelope.eventType())) {
                continue;
            }
            dispatched = true;
            // A handler exception propagates out of this method, so the offset
            // is NOT committed; Spring Kafka's DefaultErrorHandler re-seeks and
            // the record is redelivered (then dead-lettered after the retry
            // budget). The inbox idempotency check makes the redelivery safe.
            handler.handle(envelope);
        }
        if (!dispatched) {
            log.debug(
                "no handler for {} ({}) on {}-{}@{}",
                envelope.eventType(), envelope.eventId(),
                record.topic(), record.partition(), record.offset()
            );
        }
    }
}
