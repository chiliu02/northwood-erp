package com.northwood.shared.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Marker for domain events. Implementations should be records so they're
 * immutable and serialise cleanly via Jackson without configuration.
 *
 * <p>Convention: an event's {@code occurredAt} is the time of the business
 * fact, not the time of publication. The publication time lives on the
 * outbox row, not on the event itself.
 */
public interface DomainEvent {

    /** Globally-unique event identifier — also used as the outbox message ID. */
    UUID eventId();

    /** Aggregate root the event belongs to. */
    UUID aggregateId();

    /** Logical type, matching {@code outbox_message.event_type}. */
    String eventType();

    /** Schema version for this event type. Bump on incompatible changes. */
    default int eventVersion() {
        return 1;
    }

    /** When the business fact occurred. */
    Instant occurredAt();
}
