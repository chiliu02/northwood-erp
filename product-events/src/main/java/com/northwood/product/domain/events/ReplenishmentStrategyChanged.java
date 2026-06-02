package com.northwood.product.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Replenishment strategy of a SKU changed — {@code to_stock} (reorder-point
 * driven) vs {@code to_order} (order-pegged). Orthogonal to make-vs-buy; the
 * combination of the two axes yields the four operator modes (make/buy ×
 * to-stock/to-order). Per the Shape A pattern the authoritative value lives on
 * product master; consuming services keep a read-side projection. Sales reads
 * it in the fulfilment saga to decide whether a line draws from free stock
 * ({@code to_stock}) or raises dedicated, order-pegged supply ({@code to_order}).
 *
 * <p>Carries old + new wire-format values (typed enum on the aggregate,
 * {@code dbValue()} on the wire) so consumers re-projecting historical
 * decisions don't have to re-query the master. Never fires for service
 * products (their strategy is permanently NULL).
 */
public record ReplenishmentStrategyChanged(
    UUID eventId,
    UUID aggregateId,
    String oldReplenishmentStrategy,
    String newReplenishmentStrategy,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "product.ReplenishmentStrategyChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
