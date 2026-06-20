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
 * <p>State machine (the <b>pruned orchestration</b> — process progress only, not
 * the order status, which is the {@code classify(lines)} fold on the aggregate):
 * {@code started → stock_reservation_requested → supply_secured → completed}.
 * Fully-reserved orders take that happy path straight through. A partial/failed
 * reservation parks at {@code stock_reservation_incomplete} while inventory
 * replenishes (inventory is the single make-vs-buy decision + trigger point —
 * it raises the {@code ReplenishmentRequest} in the same transaction as the
 * reservation); once every short line's
 * {@code inventory.ReplenishmentFulfilled} has landed the saga re-enters
 * {@code stock_reservation_requested} to retry. Prepayment/deposit terms park at
 * the {@code awaiting_prepayment} gate until the up-front payment lands; a
 * planning fence parks at {@code awaiting_release} until the release date. Side
 * rails: {@code rejected} (a short line's replenishment was cancelled —
 * unsourceable / no BOM / no vendor), {@code compensated} (the two-phase cancel
 * path — entered directly from the active state on inventory's cancellation ack),
 * {@code failed}.
 *
 * <p>The saga is pruned to the states some control-flow branches on. The old
 * post-supply states — {@code goods_shipped}, {@code partially_shipped},
 * {@code invoice_created}, {@code invoice_partially_paid} — were non-branching
 * pass-throughs that only restated the line/360 axes, so they were dropped: after
 * {@code supply_secured} the ship → invoice → pay status is owned by the line
 * fold + the 360, and the saga's only post-supply act is the <b>completion
 * gate</b> ({@code supply_secured → completed} once
 * {@link FulfilmentSagaData#orderShipped} ∧ {@link FulfilmentSagaData#orderSettled}).
 * {@code completed} is kept — its value is derivable, but the saga's own
 * drain / compensation machinery reads the state. The prepayment/deposit
 * <em>invoice</em> intermediates ({@code awaiting_prepayment_invoice},
 * {@code awaiting_deposit_invoice}, {@code deposit_invoiced}) collapsed into the
 * single {@code awaiting_prepayment} gate; the post-payment active checkpoint
 * {@code prepaid} is <b>kept</b> (the worker branches on it to request the
 * reservation — it is not a pass-through) and now also serves deposit orders (the
 * old {@code deposit_paid} unified into it). The {@code manufacturing_*} /
 * {@code purchasing_requested} states were retired earlier when sales stopped
 * driving manufacturing directly.
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
    /**
     * Up-front-payment gate (prepayment + deposit terms). The saga parks here
     * after the order emits its prepayment/deposit invoice request and waits for
     * the up-front invoice to be fully paid (finance's
     * {@code CustomerPaymentReceived} with {@code invoiceFullySettled}). On
     * settlement the payment handler lifts the inventory shipment gate
     * ({@code SalesOrderPrepaymentSettled}) and drives the saga into the
     * reservation path — {@link #STOCK_RESERVATION_REQUESTED} directly (emitting
     * {@code StockReservationRequested}), or {@link #AWAITING_RELEASE} when a
     * planning fence is still pending.
     *
     * <p>Collapses the old {@code awaiting_prepayment_invoice → invoice_created →
     * prepaid} and deposit {@code awaiting_deposit_invoice → deposit_invoiced →
     * deposit_paid} chains: the invoice/paid intermediates were non-branching
     * pass-throughs whose facts live on the invoice/pay axes; the saga branches on
     * exactly one thing here — "is the up-front payment in?". For a deposit order
     * the balance invoice + payment land after shipment and fold into the
     * completion gate ({@link FulfilmentSagaData#orderSettled}).
     */
    public static final String AWAITING_PREPAYMENT = "awaiting_prepayment";
    /**
     * Active worker-pickup checkpoint reached once the up-front payment settles
     * (full prepayment, or a deposit). The worker picks the saga up here and runs
     * the same reservation path as {@code started} ({@code requestStockReservation}
     * — planning-fence gate, then emit {@code StockReservationRequested}). Kept
     * (not a pass-through) because the worker branches on it; unifies the old
     * {@code prepaid} + {@code deposit_paid} (the worker treated them
     * identically). The payment handler emits {@code SalesOrderPrepaymentSettled}
     * on reaching this state to lift the inventory shipment gate.
     */
    public static final String PREPAID = "prepaid";
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
    /**
     * Supply-readiness checkpoint: every line is reserved (full stock cover, or
     * an order-pegged completion). The orchestration's forward work is done here;
     * the post-supply ship → invoice → pay status is owned by the line fold + the
     * 360, and the saga's only remaining act is the <b>completion gate</b> — it
     * stays here accumulating {@link FulfilmentSagaData#orderShipped} /
     * {@link FulfilmentSagaData#orderSettled} (latched by {@code ShipmentPosted} /
     * {@code CustomerPaymentReceived}) and transitions to {@link #COMPLETED} when
     * both are met. <b>Non-terminal</b>: a cancel before shipment still moves it
     * {@code → compensated} on inventory's cancellation ack (the two-phase
     * cancel path; see {@link #COMPENSATED}). Renamed from the old
     * {@code ready_to_ship}.
     */
    public static final String SUPPLY_SECURED = "supply_secured";
    public static final String COMPLETED = "completed";
    /**
     * Non-terminal compensation drain. A cancel/reject with committed order-pegged
     * supply to withdraw (a sent PO and/or a released work order) parks here while
     * the saga waits for every leg's {@code *CancellationApplied} ack — the
     * multi-leg generalisation of the old straight-to-{@code compensated} jump.
     * Reached only when inventory's cancellation ack enumerates ≥1 PO/WO leg; a
     * cancel with nothing pegged still goes directly to {@link #COMPENSATED}. The
     * drain keeps servicing this state (it is in neither the terminal set nor the
     * worker's {@code activeStates} — it is woken by inbox acks). Empties to
     * {@link #COMPENSATED} (all legs withdrawn) or {@link #COMPENSATION_FAILED}
     * (an un-compensatable leaf refused).
     */
    public static final String COMPENSATING = "compensating";
    public static final String COMPENSATED = "compensated";
    /**
     * Terminal: compensation completed but at least one order-pegged supply leg
     * could not be withdrawn (a PO the supplier already received, a work order
     * already consuming material). The undo of such a leaf is itself a business
     * transaction (goods-receipt-and-return, scrap-WIP-with-GL-loss) out of scope
     * here, so the saga surfaces the partial failure for manual intervention via
     * {@code sales.SalesOrderCompensationFailed} rather than silently reporting a
     * clean {@code compensated}.
     *
     * <p><b>Not the same as {@link #FAILED}.</b> {@code compensation_failed} is a
     * <i>business outcome</i> — the orchestration worked exactly as designed (the
     * order was cancelled, the reservation released) and is reporting a residue a
     * human must clear (open an RMA, post a write-off). {@code failed} is a
     * <i>saga-health</i> terminal — the orchestration itself broke (an unexpected
     * error, or the retry cap exhausted). One says "the supplier already shipped,
     * do a return"; the other says "the saga is wedged, investigate". They drive
     * different ops responses, so they are deliberately distinct terminals.
     */
    public static final String COMPENSATION_FAILED = "compensation_failed";
    /**
     * Terminal: the saga itself failed to make progress — an unexpected error in
     * the forward flow, or (planned) the retry cap exhausted. Generic saga-death
     * terminal shared by all three Northwood sagas; contrast
     * {@link #COMPENSATION_FAILED}, which is a successful compensation that
     * surfaced an un-compensatable business residue, not a broken saga.
     */
    public static final String FAILED = "failed";

    private static final Set<String> TERMINAL_STATES = Set.of(
        COMPLETED, COMPENSATED, COMPENSATION_FAILED, FAILED, REJECTED
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
        AWAITING_PREPAYMENT, PREPAID,
        STOCK_RESERVATION_REQUESTED, STOCK_RESERVATION_INCOMPLETE, REJECTED,
        SUPPLY_SECURED,
        COMPLETED,
        COMPENSATING, COMPENSATED, COMPENSATION_FAILED,
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
