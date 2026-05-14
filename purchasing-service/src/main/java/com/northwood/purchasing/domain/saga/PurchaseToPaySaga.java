package com.northwood.purchasing.domain.saga;

import com.northwood.shared.domain.saga.SagaInstance;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Saga state row for the purchase-to-pay flow per purchase order.
 *
 * <p>Phase 2 implements {@code started → waiting_for_goods}: the worker
 * advances the saga out of {@code started} immediately because there's no
 * approval step today (PO is auto-sent). The {@code purchase_order_approved}
 * state in the schema's CHECK is kept reserved for a future approval workflow.
 *
 * <p>Phase 3 adds {@code waiting_for_goods → goods_received} on goods receipt.
 * Phases 4+ add invoice match → payment → completed once finance is online.
 */
public final class PurchaseToPaySaga extends SagaInstance {

    // ------------------------------------------------------------
    // State constants — Java-side ergonomic for the wire-format strings
    // stored in purchasing.purchase_to_pay_saga.saga_state.
    // ------------------------------------------------------------
    public static final String STARTED = "started";
    public static final String PURCHASE_ORDER_APPROVED = "purchase_order_approved";
    public static final String WAITING_FOR_GOODS = "waiting_for_goods";
    public static final String GOODS_RECEIVED = "goods_received";
    public static final String SUPPLIER_INVOICE_APPROVED = "supplier_invoice_approved";
    public static final String SUPPLIER_PAYMENT_MADE = "supplier_payment_made";
    public static final String COMPLETED = "completed";
    public static final String MANUAL_REVIEW_REQUIRED = "manual_review_required";
    public static final String FAILED = "failed";

    private static final Set<String> TERMINAL_STATES = Set.of(
        COMPLETED, MANUAL_REVIEW_REQUIRED, FAILED
    );

    /**
     * Every state this saga's code can transition into. Cross-checked at
     * service startup against the schema CHECK on
     * {@code purchasing.purchase_to_pay_saga.saga_state} via
     * {@code SagaStateInvariantChecker}.
     *
     * <p>No compensation states today — there's no PO cancellation flow.
     * The cancel-order saga (shipped 2026-05-06) compensates sales +
     * manufacturing only; purchasing isn't part of that flow. If a future
     * cancel-PO command lands, add {@code compensating} / {@code compensated}
     * here AND extend the schema CHECK in the same slice.
     */
    public static final Set<String> ALL_STATES = Set.of(
        STARTED,
        PURCHASE_ORDER_APPROVED,
        WAITING_FOR_GOODS, GOODS_RECEIVED,
        SUPPLIER_INVOICE_APPROVED, SUPPLIER_PAYMENT_MADE,
        COMPLETED,
        FAILED
    );

    private final UUID purchaseOrderHeaderId;

    public PurchaseToPaySaga(
        UUID sagaId,
        UUID purchaseOrderHeaderId,
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
    }

    /** Factory: a fresh saga for a newly-created PO. */
    public static PurchaseToPaySaga started(UUID purchaseOrderHeaderId) {
        Instant now = Instant.now();
        return new PurchaseToPaySaga(
            UUID.randomUUID(),
            purchaseOrderHeaderId,
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
}
