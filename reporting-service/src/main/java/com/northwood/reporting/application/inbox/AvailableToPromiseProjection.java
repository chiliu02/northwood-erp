package com.northwood.reporting.application.inbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maintains {@code reporting.available_to_promise_view}. One row per
 * product, integrating signals from inventory, manufacturing, and
 * purchasing into a single ATP picture.
 *
 * <p>Order-tolerant by design — every method uses
 * {@code INSERT ... ON CONFLICT DO UPDATE}. {@code StockReserved} and
 * {@code RawMaterialsReserved} events don't carry product SKU/name, so
 * they create stubs with {@code product_sku='(pending)'} that are later
 * backfilled by any other event that does carry the identity.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcAvailableToPromiseProjection}.
 */
public interface AvailableToPromiseProjection {

    void recordPurchaseOrderLine(
        UUID productId, String productSku, String productName,
        BigDecimal orderedQuantity, Instant occurredAt);

    void recordReceivedLine(
        UUID productId, String productSku, String productName,
        BigDecimal receivedQuantity, Instant occurredAt);

    void recordSalesReservation(UUID productId, BigDecimal reservedQuantity, Instant occurredAt);

    void recordProductionReservation(UUID productId, BigDecimal reservedQuantity, Instant occurredAt);

    void recordShippedLine(
        UUID productId, String productSku, String productName,
        BigDecimal shippedQuantity, Instant occurredAt);

    void recordWorkOrderPlanned(
        UUID productId, String productSku, String productName,
        BigDecimal plannedQuantity, Instant occurredAt);

    void recordWorkOrderCompleted(
        UUID productId, String productSku,
        BigDecimal plannedQuantity, BigDecimal completedQuantity, Instant occurredAt);

    /**
     * §3.4: backfill product identity from {@code product.ProductCreated}.
     * If a stub row already exists ({@code product_sku='(pending)'}) from
     * a reservation event arriving before the product was registered, this
     * fills in the real SKU + name without disturbing accumulator columns.
     */
    void recordProductCreated(UUID productId, String sku, String name, Instant occurredAt);

    /**
     * §1F.1: stamp {@code discontinued_at} so UI consumers can filter or
     * grey out the row. Upsert against {@code product_id}: if no row
     * exists yet (a discontinue can race ahead of any signal that would
     * have created one), inserts a stub row with {@code product_sku='(pending)'}
     * carrying just the timestamp; a later identity-bearing event backfills
     * SKU + name without disturbing the stamp.
     */
    void recordProductDiscontinued(UUID productId, Instant discontinuedAt);
}
