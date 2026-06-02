package com.northwood.purchasing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A new purchase requisition has been created. Carries enough detail for any
 * downstream consumer (e.g. a future approval workflow, a supplier-facing
 * notification) to act without having to query purchasing's schema.
 *
 * <p>The {@code sourceReplenishmentRequestId} field is populated when
 * the PR was raised by purchasing's {@code ReplenishmentRequestedHandler} in
 * response to an {@code inventory.ReplenishmentRequested} event
 * ({@code sourceType = 'stock_replenishment'}). Null for the other source
 * types.
 */
public record PurchaseRequisitionCreated(
    UUID eventId,
    UUID aggregateId,
    String requisitionNumber,
    String sourceType,
    UUID sourceWorkOrderId,
    UUID sourceProductId,
    UUID sourceReplenishmentRequestId,
    String status,
    List<RequestedLine> lines,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "purchasing.PurchaseRequisitionCreated";

    /**
     * Wire-format constants for the {@code sourceType} payload field. Cross-service
     * consumers (e.g. {@code reporting.shortage}) should reference these constants
     * rather than literals so a producer-side rename surfaces at compile time. The
     * producer-side enum is {@code PurchaseRequisition.SourceType}; same wire format,
     * different access path per the cross-service contract rule
     * (see {@code docs/conventions.md} → <i>Cross-service wire-format constants</i>).
     *
     * <p>{@code SOURCE_TYPE_WORK_ORDER_SHORTAGE} is retained for historical
     * rows; new rows from Java are emitted with {@link #SOURCE_TYPE_STOCK_REPLENISHMENT}
     * after the manufacturing-purchasing decoupling. See {@code project_235_mfg_pur_decoupling}
     * for the rationale.
     */
    public static final String SOURCE_TYPE_MANUAL = "manual";
    public static final String SOURCE_TYPE_LOW_STOCK = "low_stock";
    public static final String SOURCE_TYPE_WORK_ORDER_SHORTAGE = "work_order_shortage";
    public static final String SOURCE_TYPE_STOCK_REPLENISHMENT = "stock_replenishment";

    @Override public String eventType() { return EVENT_TYPE; }

    public record RequestedLine(
        UUID lineId,
        int lineNumber,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal requestedQuantity,
        UUID suggestedSupplierId
    ) {}
}
