package com.northwood.purchasing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The supplier price list has been authored or revised for a specific
 * (supplier, product, currency) tuple. Carries the old and new unit
 * prices so projection consumers can update their copies and accounting
 * systems can recompute weighted-average cost or post variance journals
 * as needed.
 *
 * <p>{@code aggregateId} is the {@code supplier_product_price_id} — a
 * single row in the price list. New prices for a previously-unpriced
 * tuple emit with {@code oldUnitPrice = null}.
 */
public record SupplierProductPriceChanged(
    UUID eventId,
    UUID aggregateId,
    UUID supplierId,
    UUID productId,
    String currencyCode,
    BigDecimal oldUnitPrice,
    BigDecimal newUnitPrice,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "purchasing.SupplierProductPriceChanged";

    /**
     * Wire-format aggregate-type. Carried on the event itself because there
     * is no dedicated SupplierProductPrice Java aggregate root — the price
     * list is maintained directly through the application service.
     */
    public static final String AGGREGATE_TYPE = "SupplierProductPrice";

    @Override public String eventType() { return EVENT_TYPE; }
}
