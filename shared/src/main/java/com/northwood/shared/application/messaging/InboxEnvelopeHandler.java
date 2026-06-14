package com.northwood.shared.application.messaging;

/**
 * Per-service handler for an incoming {@link EventEnvelope}.
 *
 * <p>Every Spring bean implementing this interface is auto-discovered by the
 * Kafka dispatcher (or, in tests, by an in-process bus adapter) and offered
 * each envelope. Handlers are responsible for their own idempotency — typically
 * by consulting the service's
 * {@link com.northwood.shared.application.inbox.InboxPort} before
 * applying the projection or emitting downstream commands.
 *
 * <p>Bus-agnostic by design: the same handler is invoked under the in-process
 * bus during tests and under {@code @KafkaListener}-driven dispatch in the
 * {@code kafka} profile. Application code does not see Kafka.
 */
public interface InboxEnvelopeHandler {

    /** Whether this handler cares about a given event type (e.g. {@code "product.ReorderPolicyChanged"}). */
    boolean handles(String eventType);

    /** Stable handler name persisted to the inbox row for idempotency. */
    String handlerName();

    /** Apply the envelope. Implementations must be idempotent. */
    void handle(EventEnvelope envelope);
}
