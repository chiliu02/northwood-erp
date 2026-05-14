package com.northwood.shared.application.messaging;

/**
 * Bus port. The {@link com.northwood.shared.infrastructure.outbox.OutboxPublisher}
 * writes envelopes here; what's on the other side is configurable.
 *
 * <p>Showcase mode: an in-process dispatcher that routes envelopes directly to
 * each service's inbox handler in the same JVM. No external bus required.
 *
 * <p>Production mode: a {@code KafkaTemplate}-backed implementation that
 * publishes to a topic per aggregate type. The shared kernel does not depend
 * on Kafka — that wiring lives in the service that opts in.
 */
@FunctionalInterface
public interface EventPublisher {
    void publish(EventEnvelope envelope);
}
