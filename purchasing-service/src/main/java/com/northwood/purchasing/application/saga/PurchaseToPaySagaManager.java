package com.northwood.purchasing.application.saga;

import com.northwood.purchasing.domain.saga.PurchaseToPaySaga;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Saga state-machine port for the purchase-to-pay flow. Holds saga state
 * truth — every transition the saga can take is a method here. Implementation
 * depends only on {@code PurchaseToPaySagaPort} (saga row CRUD); no
 * {@code ObjectMapper} needed because P2P saga.data is unused (always
 * {@code "{}"}). All side effects (event emission, projection writes, calls
 * into other services / aggregates) are caller's job — the worker shell for
 * worker-driven advances and the inbox handler shells for inbox-driven advances.
 *
 * <p>Each {@code applyXxx} returns the saga's new state (or its current state
 * if the transition was a no-op). Callers gate post-saga side effects on the
 * return value — e.g. {@code "completed"} triggers
 * {@code paymentProjection.markFullyPaid}, {@code "supplier_payment_made"}
 * triggers {@code paymentProjection.addPartialPayment}.
 *
 * <p>State machine documented in {@code PurchaseToPaySaga.md} at the repo root.
 */
public interface PurchaseToPaySagaManager {

    // ------------------------------------------------------------
    // Lifecycle (called from PurchaseOrderService)
    // ------------------------------------------------------------

    /**
     * Insert a fresh saga at {@code started}. Called from
     * {@code PurchaseOrderService.convertFromRequisition} (and any other PO
     * creation path) in the same transaction as the PO insert.
     */
    void insertStarted(UUID purchaseOrderHeaderId);

    /**
     * Flip {@code started → purchase_order_approved}. Idempotent: a saga
     * already past {@code started} is left alone (returns its current state).
     * Returns null when no saga exists for the PO. Called from
     * {@code PurchaseOrderService.convertFromRequisition} (auto-approve path)
     * and {@code PurchaseOrderService.approve} (manual REST path).
     */
    String approve(UUID purchaseOrderHeaderId);

    // ------------------------------------------------------------
    // Worker drain (called from PurchaseToPaySagaWorker)
    // ------------------------------------------------------------

    /**
     * Drain due sagas via the abstract {@code SagaManager<S, P>} base. The
     * worker shell supplies its own {@code workerId} and an {@code advanceFn}
     * that runs the only worker-driven transition: {@code purchase_order_approved
     * → waiting_for_goods}.
     */
    void drain(int batchSize, String workerId, Consumer<PurchaseToPaySaga> advanceFn);

    // ------------------------------------------------------------
    // Inbox-driven transitions
    // ------------------------------------------------------------

    /**
     * Apply {@code inventory.GoodsReceived}. When {@code fullyReceived} is
     * true and the saga is in {@code waiting_for_goods}, transitions
     * {@code → goods_received}. Otherwise returns the unchanged state.
     */
    String applyGoodsReceived(UUID purchaseOrderHeaderId, boolean fullyReceived);

    /**
     * Apply {@code finance.SupplierInvoiceApproved}. Transitions
     * {@code goods_received → supplier_invoice_approved}; ignored from any
     * other state.
     */
    String applySupplierInvoiceApproved(UUID purchaseOrderHeaderId);

    /**
     * Apply {@code finance.SupplierInvoiceRejected} (manual reject of a
     * parked-at-three_way_match_failed invoice). Transitions
     * {@code goods_received → failed} so the saga lands in a terminal
     * state. Ignored from any other state (the saga is either already
     * past invoice approval or hasn't reached goods receipt yet).
     */
    String applySupplierInvoiceRejected(UUID purchaseOrderHeaderId);

    /**
     * Apply {@code finance.SupplierPaymentMade}. On full settlement,
     * transitions to {@code completed}. On partial, transitions to
     * {@code supplier_payment_made} (or stays there for subsequent partials).
     * Accepted from {@code supplier_invoice_approved} or
     * {@code supplier_payment_made}; ignored otherwise.
     */
    String applySupplierPaymentMade(UUID purchaseOrderHeaderId, boolean fullySettled);
}
