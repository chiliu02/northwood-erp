package com.northwood.purchasing.domain.saga;

import com.northwood.purchasing.domain.PurchasingAggregateTypes;
import com.northwood.shared.domain.saga.SagaInstance;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Saga state row for the purchase-to-pay flow per purchase order.
 *
 * <p>Walks {@code started → purchase_order_approved → waiting_for_goods →
 * goods_received → supplier_invoice_approved → completed} on the happy
 * path. Branches: partial supplier payments park at {@code supplier_partially_paid}
 * (resume on the next allocation); manual rejection of a parked-at-3WM-failed
 * invoice lands the saga in terminal {@code failed} via
 * {@code applySupplierInvoiceRejected}.
 */
public final class PurchaseToPaySaga extends SagaInstance {

    /**
     * Wire-format aggregate-type reserved for events this saga emits under its
     * own identity. Currently unused — the worker only performs internal state
     * transitions (no outbox emissions today), and all other transitions are
     * inbox-handler-driven against the {@code PurchaseOrder} /
     * {@code SupplierInvoice} / {@code Payment} aggregates, which stamp their
     * own aggregate-types. Declared for symmetry with
     * {@code SalesOrderFulfilmentSaga} and as the stable call site for any
     * future saga-originated commands.
     */
    public static final String AGGREGATE_TYPE = PurchasingAggregateTypes.PURCHASE_TO_PAY_SAGA;

    // ------------------------------------------------------------
    // State constants — Java-side ergonomic for the wire-format strings
    // stored in purchasing.purchase_to_pay_saga.saga_state.
    // ------------------------------------------------------------
    public static final String STARTED = "started";
    public static final String PURCHASE_ORDER_APPROVED = "purchase_order_approved";
    public static final String WAITING_FOR_GOODS = "waiting_for_goods";
    public static final String GOODS_RECEIVED = "goods_received";
    public static final String SUPPLIER_INVOICE_APPROVED = "supplier_invoice_approved";
    public static final String SUPPLIER_PARTIALLY_PAID = "supplier_partially_paid";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    /** Terminal — the PO was rejected while still a draft (manual reject). */
    public static final String CANCELLED = "cancelled";

    private static final Set<String> TERMINAL_STATES = Set.of(
        COMPLETED, FAILED, CANCELLED
    );

    /**
     * Every state this saga's code can transition into. Cross-checked at
     * service startup against the schema CHECK on
     * {@code purchasing.purchase_to_pay_saga.saga_state} via
     * {@code SagaStateInvariantChecker}.
     *
     * <p>{@code cancelled} is the terminal state for a manual reject of a draft
     * PO ({@code started → cancelled}) — a clean termination, not a compensation
     * (nothing downstream happened on a draft, so there's nothing to undo). A
     * future cancel-after-sent flow would need real {@code compensating} /
     * {@code compensated} states added here AND in the schema CHECK in the same
     * slice; the draft reject deliberately doesn't.
     */
    public static final Set<String> ALL_STATES = Set.of(
        STARTED,
        PURCHASE_ORDER_APPROVED,
        WAITING_FOR_GOODS, GOODS_RECEIVED,
        SUPPLIER_INVOICE_APPROVED, SUPPLIER_PARTIALLY_PAID,
        COMPLETED,
        FAILED,
        CANCELLED
    );

    private final UUID purchaseOrderHeaderId;
    /**
     * Originating sales order (cross-saga key). Non-null only when the PO
     * traces back to a sales-order-driven replenishment ({@code
     * sales_order_shortage} or {@code order_pegged}); null for manual /
     * reorder-point PRs. Set once at creation, immutable, and stamped onto every
     * saga-milestone span as {@code northwood.sales_order_id}.
     */
    private final UUID salesOrderHeaderId;

    public PurchaseToPaySaga(
        UUID sagaId,
        UUID purchaseOrderHeaderId,
        UUID salesOrderHeaderId,
        String state,
        String currentStep,
        String lastError,
        int retryCount,
        Instant nextRetryAt,
        String leaseOwner,
        Instant leaseExpiresAt,
        long version,
        String dataJson,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
    ) {
        super(sagaId, state, currentStep, lastError, retryCount, nextRetryAt,
              leaseOwner, leaseExpiresAt, version, dataJson, createdAt, updatedAt, completedAt);
        this.purchaseOrderHeaderId = purchaseOrderHeaderId;
        this.salesOrderHeaderId = salesOrderHeaderId;
    }

    /**
     * Factory: a fresh saga for a newly-created PO. {@code salesOrderHeaderId}
     * is the cross-saga key — non-null when the PO traces back to a
     * sales-order-driven replenishment, null otherwise.
     */
    public static PurchaseToPaySaga started(UUID purchaseOrderHeaderId, UUID salesOrderHeaderId) {
        Instant now = Instant.now();
        return new PurchaseToPaySaga(
            UUID.randomUUID(),
            purchaseOrderHeaderId,
            salesOrderHeaderId,
            STARTED,
            "wait_for_worker_pickup",
            null,
            0,
            now,
            null,
            null,
            0L,
            "{}",
            now,
            now,
            null
        );
    }

    @Override
    public Set<String> terminalStates() {
        return TERMINAL_STATES;
    }

    public UUID purchaseOrderHeaderId() { return purchaseOrderHeaderId; }
    public UUID salesOrderHeaderId() { return salesOrderHeaderId; }
}
