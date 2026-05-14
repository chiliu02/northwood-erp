package com.northwood.product.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Make-vs-buy classification of a SKU. Drives replenishment routing in
 * downstream services: manufacturing decides whether to release a work
 * order ({@code is_manufactured}); purchasing decides whether the SKU is on
 * the procurement catalogue ({@code is_purchased}). Carries old + new for
 * each flag so consumers projecting historical decisions don't have to
 * re-query the master.
 *
 * <p>Both flags can be true (vertically integrated; we make some and buy
 * some), but at least one must be true at the producer side — a SKU that's
 * neither makeable nor buyable is unsourceable. The aggregate enforces this
 * invariant.
 */
public record MakeVsBuyChanged(
    UUID eventId,
    UUID aggregateId,
    boolean oldIsPurchased,
    boolean newIsPurchased,
    boolean oldIsManufactured,
    boolean newIsManufactured,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "product.MakeVsBuyChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
