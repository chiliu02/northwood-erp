package com.northwood.reporting.application.inbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maintains {@code reporting.material_shortage_view}. One row per
 * material product, reflecting the current shortage state and any
 * in-flight remediation (purchase requisition / purchase order / goods
 * receipt).
 *
 * <p>Order-tolerant by design: every method uses
 * {@code INSERT ... ON CONFLICT DO UPDATE}.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcMaterialShortageProjection}.
 */
public interface MaterialShortageProjection {

    /**
     * One call per shortage component on a {@code RawMaterialShortageDetected}
     * event.
     */
    void recordShortageComponent(
        UUID materialProductId,
        String materialSku,
        String materialName,
        BigDecimal shortageQuantity,
        Instant occurredAt);

    /** Per requested line on a {@code PurchaseRequisitionCreated} event. */
    void recordRequisitionLine(
        UUID productId,
        String productSku,
        String productName,
        Instant occurredAt);

    /** Per line on a {@code PurchaseOrderCreated} event. */
    void recordPurchaseOrderLine(
        UUID productId,
        String productSku,
        String productName,
        BigDecimal orderedQuantity,
        Instant occurredAt);

    /** Per received line on a {@code GoodsReceived} event. */
    void recordReceivedLine(
        UUID productId,
        String productSku,
        String productName,
        BigDecimal receivedQuantity,
        Instant occurredAt);
}
