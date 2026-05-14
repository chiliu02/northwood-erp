package com.northwood.sales.domain.saga;

import com.northwood.shared.domain.saga.SagaInstance;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Saga state row for the sales-order fulfilment flow. Adds the
 * {@code sales_order_id} domain key on top of the lease/version columns
 * inherited from {@link SagaInstance}.
 *
 * <p>State machine (per v3 schema CHECK constraint):
 * {@code started → stock_reservation_requested → stock_reserved →
 *  manufacturing_requested → manufacturing_in_progress → manufacturing_completed →
 *  ready_to_ship → goods_shipped → invoice_requested → invoice_created → completed}.
 * Fully-reserved orders shortcut directly from
 * {@code stock_reservation_requested → ready_to_ship}, skipping the
 * manufacturing leg. Side rails: {@code stock_reservation_failed},
 * {@code compensating}, {@code compensated}, {@code failed}.
 *
 * <p>This slice implements the first two transitions only; later transitions
 * arrive when the consuming services are fleshed out.
 */
public final class SalesOrderFulfilmentSaga extends SagaInstance {

    // ------------------------------------------------------------
    // State constants — single source of truth for the wire-format strings
    // stored in sales.sales_order_fulfilment_saga.saga_state.
    // The DB CHECK constraint and event payloads keep the underlying
    // strings as the canonical form; these constants are a Java-side
    // ergonomic that catches typos at compile time and makes find-usages
    // for "where is this state written?" trivial.
    // ------------------------------------------------------------
    public static final String STARTED = "started";
    public static final String STOCK_RESERVATION_REQUESTED = "stock_reservation_requested";
    public static final String STOCK_RESERVED = "stock_reserved";
    public static final String STOCK_RESERVATION_FAILED = "stock_reservation_failed";
    public static final String MANUFACTURING_REQUESTED = "manufacturing_requested";
    public static final String MANUFACTURING_IN_PROGRESS = "manufacturing_in_progress";
    public static final String MANUFACTURING_COMPLETED = "manufacturing_completed";
    public static final String READY_TO_SHIP = "ready_to_ship";
    public static final String GOODS_SHIPPED = "goods_shipped";
    public static final String INVOICE_REQUESTED = "invoice_requested";
    public static final String INVOICE_CREATED = "invoice_created";
    public static final String INVOICE_PAID = "invoice_paid";
    public static final String COMPLETED = "completed";
    public static final String COMPENSATING = "compensating";
    public static final String COMPENSATED = "compensated";
    public static final String FAILED = "failed";

    private static final Set<String> TERMINAL_STATES = Set.of(COMPLETED, COMPENSATED, FAILED);

    /**
     * Every state this saga's code can transition into. Cross-checked at
     * service startup against the schema CHECK on
     * {@code sales.sales_order_fulfilment_saga.saga_state} via
     * {@code SagaStateInvariantChecker} — fails fast if the DB list is
     * missing any name.
     */
    public static final Set<String> ALL_STATES = Set.of(
        STARTED,
        STOCK_RESERVATION_REQUESTED, STOCK_RESERVED, STOCK_RESERVATION_FAILED,
        MANUFACTURING_REQUESTED, MANUFACTURING_IN_PROGRESS, MANUFACTURING_COMPLETED,
        READY_TO_SHIP, GOODS_SHIPPED,
        INVOICE_REQUESTED, INVOICE_CREATED, INVOICE_PAID,
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
