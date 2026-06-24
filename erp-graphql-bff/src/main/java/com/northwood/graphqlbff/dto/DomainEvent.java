package com.northwood.graphqlbff.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Flattened view of an {@code EventEnvelope} decoded off a {@code *.events}
 * Kafka topic. Backs the GraphQL {@code DomainEvent} type emitted by
 * {@code Subscription.events}.
 *
 * <p>{@code occurredAt} is an {@link OffsetDateTime} (not {@code Instant}): the
 * graphql-java {@code DateTime} scalar serialises {@code OffsetDateTime} /
 * {@code ZonedDateTime} but not {@code Instant}, so the decoder parses the
 * envelope's ISO timestamp into an {@code OffsetDateTime}.
 */
public record DomainEvent(
    UUID eventId,
    String eventType,
    String sourceService,
    String aggregateType,
    UUID aggregateId,
    OffsetDateTime occurredAt
) {}
