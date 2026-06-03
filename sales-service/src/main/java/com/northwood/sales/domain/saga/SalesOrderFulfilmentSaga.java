package com.northwood.sales.domain.saga;

import com.northwood.sales.domain.SalesAggregateTypes;
import com.northwood.shared.domain.saga.SagaInstance;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Saga state row for the sales-order fulfilment flow. Adds the
 * {@code sales_order_id} domain key on top of the lease/version columns
 * inherited from {@link SagaInstance}.
 *
 * <p>State machine (per the schema CHECK constraint):
 * {@code started → stock_reservation_requested → ready_to_ship → goods_shipped
 *  → invoice_created → completed}.
 * Fully-reserved orders take that happy path straight through. A partial/failed
 * reservation parks at {@code stock_reservation_incomplete} while inventory
 * replenishes (inventory is the single make-vs-buy decision + trigger point —
 * it raises the {@code ReplenishmentRequest} in the same transaction as the
 * reservation); once every short line's
 * {@code inventory.ReplenishmentFulfilled} has landed the saga re-enters
 * {@code stock_reservation_requested} to retry. Side rails: {@code rejected}
 * (a short line's replenishment was cancelled — unsourceable / no BOM / no
 * vendor), {@code compensating}, {@code compensated}, {@code failed}.
 *
 * <p>The {@code manufacturing_*} / {@code purchasing_requested} states were
 * retired when sales stopped driving manufacturing directly.
 * The DB CHECK constraint still lists them (a fresh-volume migration concern,
 * not a code one); the {@code SagaStateInvariantChecker} only fails if code
 * writes a state the DB rejects, so dropping them here is safe.
 */
public final class SalesOrderFulfilmentSaga extends SagaInstance {

    /**
     * Wire-format aggregate-type stamped onto {@code sales.outbox_message.aggregate_type}
     * for events this saga emits (it owns its emissions independently of the
     * SalesOrder aggregate; the saga's lifecycle is the originator).
     */
    public static final String AGGREGATE_TYPE = SalesAggregateTypes.SALES_ORDER_FULFILMENT_SAGA;

    // ------------------------------------------------------------
    // State constants — single source of truth for the wire-format strings
    // stored in sales.sales_order_fulfilment_saga.saga_state.
    // The DB CHECK constraint and event payloads keep the underlying
    // strings as the canonical form; these constants are a Java-side
    // ergonomic that catches typos at compile time and makes find-usages
    // for "where is this state written?" trivial.
    // ------------------------------------------------------------
    public static final String STARTED = "started";
    /**
     * Planning-time-fence parked state: the order's need-by is far enough out
     * that {@code need-by − max line fence} is still in the future, so the saga
     * defers its stock reservation until then. The worker stamps
     * {@code parkUntil(releaseAt)} on entry; the {@code @Scheduled poll()}
     * re-claims the row once {@code next_retry_at <= now()} (this state is in
     * {@code activeStates()} because it is woken by wall-clock time, not an
     * inbound event). On wake the worker emits {@code StockReservationRequested}
     * unconditionally — the park is the decision, so it does NOT re-evaluate the
     * release date (see {@code docs/sagas.md} → Timed releases — park-and-wake,
     * decide once). A cancel from this state has nothing reserved; inventory
     * acks the compensation with {@code released = 0} so the saga still reaches
     * {@code compensated}.
     */
    public static final String AWAITING_RELEASE = "awaiting_release";
    public static final String STOCK_RESERVATION_REQUESTED = "stock_reservation_requested";
    /**
     * Parked state: reservation came back partial/failed and inventory has
     * raised a {@code ReplenishmentRequest} (in the same transaction) for each
     * short line. The saga waits here for every outstanding
     * {@code inventory.ReplenishmentFulfilled} — at which point it re-enters
     * {@link #STOCK_RESERVATION_REQUESTED} to retry reservation against the
     * now-restocked inventory. A {@code ReplenishmentCancelled} for any short
     * line moves the saga to {@link #REJECTED} instead. The worker no longer
     * acts on this state (the old {@code stock_reservation_incomplete →
     * manufacturing_requested} leg was retired).
     */
    public static final String STOCK_RESERVATION_INCOMPLETE = "stock_reservation_incomplete";
    public static final String REJECTED = "rejected";
    public static final String READY_TO_SHIP = "ready_to_ship";
    public static final String GOODS_SHIPPED = "goods_shipped";
    public static final String INVOICE_REQUESTED = "invoice_requested";
    public static final String INVOICE_CREATED = "invoice_created";
    public static final String INVOICE_PARTIALLY_PAID = "invoice_partially_paid";
    // Prepayment branch states. AWAITING_PREPAYMENT_INVOICE parks the saga
    // after PrepaymentInvoiceRequested until finance acks with CustomerInvoiceCreated.
    // PREPAID is the active worker-pickup checkpoint between full payment receipt
    // and stock reservation request (the saga continues from PREPAID into the
    // same stock-reservation path as the on-shipment flow).
    public static final String AWAITING_PREPAYMENT_INVOICE = "awaiting_prepayment_invoice";
    public static final String PREPAID = "prepaid";
    // Deposit branch states: AWAITING_DEPOSIT_INVOICE parks after
    // DepositInvoiceRequested until finance acks with CustomerInvoiceCreated;
    // DEPOSIT_INVOICED waits for the deposit payment; DEPOSIT_PAID is the active
    // worker-pickup checkpoint (like PREPAID) between deposit settlement and the
    // stock-reservation request. The balance cycle after shipment reuses the
    // on-shipment GOODS_SHIPPED → INVOICE_CREATED → COMPLETED tail.
    public static final String AWAITING_DEPOSIT_INVOICE = "awaiting_deposit_invoice";
    public static final String DEPOSIT_INVOICED = "deposit_invoiced";
    public static final String DEPOSIT_PAID = "deposit_paid";
    public static final String COMPLETED = "completed";
    public static final String COMPENSATING = "compensating";
    public static final String COMPENSATED = "compensated";
    public static final String FAILED = "failed";

    private static final Set<String> TERMINAL_STATES = Set.of(
        COMPLETED, COMPENSATED, FAILED, REJECTED
    );

    /**
     * Every state this saga's code can transition into. Cross-checked at
     * service startup against the schema CHECK on
     * {@code sales.sales_order_fulfilment_saga.saga_state} via
     * {@code SagaStateInvariantChecker} — fails fast if the DB list is
     * missing any name.
     */
    public static final Set<String> ALL_STATES = Set.of(
        STARTED,
        AWAITING_RELEASE,
        STOCK_RESERVATION_REQUESTED, STOCK_RESERVATION_INCOMPLETE, REJECTED,
        AWAITING_PREPAYMENT_INVOICE, PREPAID,
        AWAITING_DEPOSIT_INVOICE, DEPOSIT_INVOICED, DEPOSIT_PAID,
        READY_TO_SHIP, GOODS_SHIPPED,
        INVOICE_REQUESTED, INVOICE_CREATED, INVOICE_PARTIALLY_PAID,
        COMPLETED,
        COMPENSATING, COMPENSATED,
        FAILED
    );

    private final UUID salesOrderId;

    public SalesOrderFulfilmentSaga(
        UUID sagaId,
        UUID salesOrderId,
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
        this.salesOrderId = salesOrderId;
    }

    public static SalesOrderFulfilmentSaga started(UUID salesOrderId, String dataJson) {
        Instant now = Instant.now();
        return new SalesOrderFulfilmentSaga(
            UUID.randomUUID(),
            salesOrderId,
            STARTED,
            "wait_for_worker_pickup",
            null,
            0,
            now,
            null,
            null,
            0L,
            dataJson == null ? "{}" : dataJson,
            now,
            now,
            null
        );
    }

    @Override
    public Set<String> terminalStates() {
        return TERMINAL_STATES;
    }

    public UUID salesOrderId() { return salesOrderId; }
}
