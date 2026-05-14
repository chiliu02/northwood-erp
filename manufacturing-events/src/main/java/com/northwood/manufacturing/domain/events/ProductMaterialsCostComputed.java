package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * §2.8 Slice C: emitted by the manufacturing-side materialsCost rollup
 * engine whenever a product's computed cost transitions to a new value
 * (or to/from null). The cost itself lives on
 * {@code manufacturing.product_materials_cost} (manufacturing's own data
 * of record) — the event is the public contract so other services /
 * read-models can react.
 *
 * <p>{@code materialsCost} and {@code currencyCode} are nullable together:
 * when no cost can be computed (no preferred vendor, no supplier price,
 * BoM not activated for a manufactured item, etc.), both are null and
 * {@code reason} is {@code "inputs_missing"}. Consumers that surface the
 * value to a human should render "—" or "n/a" in that case.
 *
 * <p>{@code reason} values for Slice C: {@code "supplier_price_change"},
 * {@code "inputs_missing"}. Slice D will add {@code "bom_activated"},
 * {@code "bom_line_changed"}, {@code "child_materials_cost_changed"}.
 *
 * <p>Pure read-side concern — emitted directly from the service, not from
 * an aggregate. Mirrors {@link WorkOrderPriorityChanged} in that respect.
 */
public record ProductMaterialsCostComputed(
    UUID eventId,
    UUID aggregateId,        // product_id
    BigDecimal materialsCost, // nullable
    String currencyCode,      // nullable; ISO-4217
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.ProductMaterialsCostComputed";

    /**
     * Wire-format aggregate-type. Carried here (not on the Product aggregate
     * class) because manufacturing-service cannot import product-service Java
     * classes; the emitting service in manufacturing needs a local reference.
     */
    public static final String AGGREGATE_TYPE = "Product";

    @Override public String eventType() { return EVENT_TYPE; }
}
