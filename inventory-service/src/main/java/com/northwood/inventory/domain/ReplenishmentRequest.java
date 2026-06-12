package com.northwood.inventory.domain;

import com.northwood.inventory.domain.events.ReplenishmentCancelled;
import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root for an inventory-orchestrated replenishment.
 * One row per request; lifecycle is linear:
 *
 * <pre>
 *   requested  ──(ReplenishmentDispatched)──▶  dispatched
 *      │                                                    │
 *      │                                                    ▼
 *      │                                  (WorkOrderManufacturingCompleted
 *      │                                          or GoodsReceived match)
 *      │                                                    │
 *      ▼                                                    ▼
 *   cancelled                                           fulfilled
 * </pre>
 *
 * <p>The one-open-per-(product, warehouse) invariant is enforced by a partial
 * unique index on the table; the factory does NOT pre-check — a concurrent
 * detection thread could insert between the check and the insert. Catch the
 * unique-violation at the {@code save} call site instead (the detection
 * service treats it as a debug-logged no-op, matching the semantic intent of
 * "ignore the second trigger while the first is open").
 *
 * <p>Status transitions cover {@code requested}, {@code cancelled},
 * {@code dispatched}, and {@code fulfilled} via {@code markDispatched} +
 * {@code linkPurchaseOrder} + {@code markFulfilled} on the close-the-loop path.
 */
public final class ReplenishmentRequest {

    /**
     * Wire-format aggregate-type stamped onto
     * {@code inventory.outbox_message.aggregate_type} for events this
     * aggregate emits.
     */
    public static final String AGGREGATE_TYPE = InventoryAggregateTypes.REPLENISHMENT_REQUEST;

    /**
     * Replenishment lifecycle status. Mirrors the schema CHECK on
     * {@code inventory.replenishment_request.status}.
     */
    public enum Status {
        REQUESTED("requested"),
        DISPATCHED("dispatched"),
        FULFILLED("fulfilled"),
        CANCELLED("cancelled");

        private final String code;

        Status(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Status fromCode(String value) {
            for (Status s : values()) {
                if (s.code.equals(value)) return s;
            }
            throw Assert.unknownValue("replenishment request status", value);
        }
    }

    /**
     * Which downstream service handles the request. Derived by the detection
     * service from the SKU's make-vs-buy classification (snapshotted into
     * inventory by the {@code ProductCardProjection}).
     */
    public enum TargetService {
        MANUFACTURING("manufacturing"),
        PURCHASING("purchasing");

        private final String code;

        TargetService(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static TargetService fromCode(String value) {
            for (TargetService t : values()) {
                if (t.code.equals(value)) return t;
            }
            throw Assert.unknownValue("replenishment target service", value);
        }
    }

    /**
     * Why the request was raised. Mirrors the schema CHECK on
     * {@code inventory.replenishment_request.reason}.
     */
    public enum Reason {
        /** On-hand crossed below the reorder point at a balance-decrement site. */
        REORDER_POINT_BREACH("reorder_point_breach"),
        /** WO release found short raw materials, routed via inventory. */
        WORK_ORDER_SHORTAGE("work_order_shortage"),
        /**
         * A sales order line is short on stock — the saga's partial-reservation
         * path routes shortages through inventory (routed by make-vs-buy). The
         * {@code source_sales_order_line_id} column carries the back-reference so
         * the eventual {@code ReplenishmentFulfilled} can un-park the fulfilment
         * saga.
         */
        SALES_ORDER_SHORTAGE("sales_order_shortage"),
        /**
         * An order-pegged ({@code to_order}) sales-order line. Unlike
         * {@link #SALES_ORDER_SHORTAGE}, the line never draws from the shared
         * pool: sales raises dedicated supply for the FULL line quantity,
         * earmarked to that line. Same back-reference + per-line multiplicity as
         * sales_order_shortage; the peg-on-completion step reserves
         * the eventual output for the SO line.
         */
        ORDER_PEGGED("order_pegged");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Reason fromCode(String value) {
            for (Reason r : values()) {
                if (r.code.equals(value)) return r;
            }
            throw Assert.unknownValue("replenishment request reason", value);
        }
    }

    /**
     * Which kind of downstream aggregate is fulfilling this replenishment.
     * Populated by {@link #markDispatched(DispatchedAggregateKind, UUID)} when
     * the close-the-loop handler receives the corresponding
     * {@code ReplenishmentDispatched} event.
     */
    public enum DispatchedAggregateKind {
        WORK_ORDER("work_order"),
        PURCHASE_REQUISITION("purchase_requisition");

        private final String code;

        DispatchedAggregateKind(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static DispatchedAggregateKind fromCode(String value) {
            for (DispatchedAggregateKind k : values()) {
                if (k.code.equals(value)) return k;
            }
            throw Assert.unknownValue("replenishment dispatched_aggregate_kind", value);
        }
    }

    private final ReplenishmentRequestId id;
    private final UUID productId;
    private final UUID warehouseId;
    private final BigDecimal requestedQuantity;
    private final TargetService targetService;
    private final Reason reason;
    /**
     * Sales-order back-reference (saga key). Non-null iff
     * {@code reason ∈ {SALES_ORDER_SHORTAGE, ORDER_PEGGED}} — identifies the
     * sales-order header whose fulfilment saga is awaiting this replenishment.
     * Sales is keyed by header id, not line id, so this is what the fan-in
     * handler uses to find the saga. Sibling of {@link #sourceSalesOrderLineId}
     * (same nullable semantic).
     */
    private final UUID sourceSalesOrderHeaderId;
    /**
     * Sales-order back-reference (line within the saga). Non-null iff
     * {@code reason ∈ {SALES_ORDER_SHORTAGE, ORDER_PEGGED}} — identifies the
     * specific sales-order line on the addressed saga so the fan-in handler can
     * remove just that line's entry from the saga's
     * {@code outstandingReplenishmentLineIds} set.
     */
    private final UUID sourceSalesOrderLineId;
    private Status status;
    private DispatchedAggregateKind dispatchedAggregateKind;
    private UUID dispatchedAggregateId;
    private UUID linkedPurchaseOrderId;
    private Instant dispatchedAt;
    private Instant fulfilledAt;
    private Instant cancelledAt;
    private final long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /**
     * Factory: raise a new replenishment for a {@code reorder_point_breach} or
     * {@code work_order_shortage} reason — no sales-order back-reference.
     * Emits {@link ReplenishmentRequested}.
     */
    public static ReplenishmentRequest request(
        UUID productId,
        UUID warehouseId,
        BigDecimal requestedQuantity,
        TargetService targetService,
        Reason reason
    ) {
        Assert.argument(reason == Reason.REORDER_POINT_BREACH || reason == Reason.WORK_ORDER_SHORTAGE,
            "use requestForSalesOrderShortage(...) / requestForOrderPegged(...) for sales-order-backed reasons");
        return doRequest(productId, warehouseId, requestedQuantity, targetService, reason, null, null);
    }

    /**
     * Factory: raise a new replenishment for a sales-order partial-reservation
     * shortfall. Stamps both {@code sourceSalesOrderHeaderId} (saga key) and
     * {@code sourceSalesOrderLineId} (line within saga) so the eventual
     * {@code ReplenishmentFulfilled} carries the full address needed by sales'
     * fan-in handler. Emits {@link ReplenishmentRequested}.
     */
    public static ReplenishmentRequest requestForSalesOrderShortage(
        UUID productId,
        UUID warehouseId,
        BigDecimal requestedQuantity,
        TargetService targetService,
        UUID sourceSalesOrderHeaderId,
        UUID sourceSalesOrderLineId
    ) {
        Assert.notNull(sourceSalesOrderHeaderId, "sourceSalesOrderHeaderId");
        Assert.notNull(sourceSalesOrderLineId, "sourceSalesOrderLineId");
        return doRequest(productId, warehouseId, requestedQuantity, targetService,
            Reason.SALES_ORDER_SHORTAGE, sourceSalesOrderHeaderId, sourceSalesOrderLineId);
    }

    /**
     * Factory: raise a dedicated, order-pegged replenishment for a {@code to_order}
     * sales-order line. Same shape + back-references as
     * {@link #requestForSalesOrderShortage} but {@code reason = ORDER_PEGGED} —
     * the request is for the FULL line quantity and the eventual output is
     * pegged to the SO line on completion. Emits
     * {@link ReplenishmentRequested}.
     */
    public static ReplenishmentRequest requestForOrderPegged(
        UUID productId,
        UUID warehouseId,
        BigDecimal requestedQuantity,
        TargetService targetService,
        UUID sourceSalesOrderHeaderId,
        UUID sourceSalesOrderLineId
    ) {
        Assert.notNull(sourceSalesOrderHeaderId, "sourceSalesOrderHeaderId");
        Assert.notNull(sourceSalesOrderLineId, "sourceSalesOrderLineId");
        return doRequest(productId, warehouseId, requestedQuantity, targetService,
            Reason.ORDER_PEGGED, sourceSalesOrderHeaderId, sourceSalesOrderLineId);
    }

    private static ReplenishmentRequest doRequest(
        UUID productId,
        UUID warehouseId,
        BigDecimal requestedQuantity,
        TargetService targetService,
        Reason reason,
        UUID sourceSalesOrderHeaderId,
        UUID sourceSalesOrderLineId
    ) {
        Assert.notNull(productId, "productId");
        Assert.notNull(warehouseId, "warehouseId");
        Assert.notNull(requestedQuantity, "requestedQuantity");
        Assert.argument(requestedQuantity.signum() > 0,
            "requestedQuantity must be positive, got " + requestedQuantity);
        Assert.notNull(targetService, "targetService");
        Assert.notNull(reason, "reason");

        ReplenishmentRequestId id = ReplenishmentRequestId.newId();
        ReplenishmentRequest r = new ReplenishmentRequest(
            id, productId, warehouseId, requestedQuantity,
            targetService, reason, sourceSalesOrderHeaderId, sourceSalesOrderLineId,
            Status.REQUESTED,
            null, null, null, null, null, null,
            0L
        );
        r.pendingEvents.add(new ReplenishmentRequested(
            UUID.randomUUID(),
            id.value(),
            productId,
            warehouseId,
            requestedQuantity,
            targetService.code(),
            reason.code(),
            sourceSalesOrderHeaderId,
            Instant.now()
        ));
        return r;
    }

    /** Factory: hydrate from the DB; emits no events. */
    public static ReplenishmentRequest reconstitute(
        ReplenishmentRequestId id,
        UUID productId,
        UUID warehouseId,
        BigDecimal requestedQuantity,
        TargetService targetService,
        Reason reason,
        UUID sourceSalesOrderHeaderId,
        UUID sourceSalesOrderLineId,
        Status status,
        DispatchedAggregateKind dispatchedAggregateKind,
        UUID dispatchedAggregateId,
        UUID linkedPurchaseOrderId,
        Instant dispatchedAt,
        Instant fulfilledAt,
        Instant cancelledAt,
        long version
    ) {
        return new ReplenishmentRequest(
            id, productId, warehouseId, requestedQuantity,
            targetService, reason, sourceSalesOrderHeaderId, sourceSalesOrderLineId, status,
            dispatchedAggregateKind, dispatchedAggregateId, linkedPurchaseOrderId,
            dispatchedAt, fulfilledAt, cancelledAt,
            version
        );
    }

    private ReplenishmentRequest(
        ReplenishmentRequestId id,
        UUID productId,
        UUID warehouseId,
        BigDecimal requestedQuantity,
        TargetService targetService,
        Reason reason,
        UUID sourceSalesOrderHeaderId,
        UUID sourceSalesOrderLineId,
        Status status,
        DispatchedAggregateKind dispatchedAggregateKind,
        UUID dispatchedAggregateId,
        UUID linkedPurchaseOrderId,
        Instant dispatchedAt,
        Instant fulfilledAt,
        Instant cancelledAt,
        long version
    ) {
        this.id = id;
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.requestedQuantity = requestedQuantity;
        this.targetService = targetService;
        this.reason = reason;
        this.sourceSalesOrderHeaderId = sourceSalesOrderHeaderId;
        this.sourceSalesOrderLineId = sourceSalesOrderLineId;
        this.status = status;
        this.dispatchedAggregateKind = dispatchedAggregateKind;
        this.dispatchedAggregateId = dispatchedAggregateId;
        this.linkedPurchaseOrderId = linkedPurchaseOrderId;
        this.dispatchedAt = dispatchedAt;
        this.fulfilledAt = fulfilledAt;
        this.cancelledAt = cancelledAt;
        this.version = version;
    }

    /**
     * Mark this request as dispatched to the named downstream aggregate.
     * Idempotent when the same kind+id pair re-arrives (a redelivered
     * {@code ReplenishmentDispatched} → no-op). Rejects transitions from any
     * non-{@code REQUESTED} state — fulfilled / cancelled aggregates can't
     * be re-dispatched.
     */
    public void markDispatched(DispatchedAggregateKind kind, UUID dispatchedAggregateId) {
        Assert.notNull(kind, "kind");
        Assert.notNull(dispatchedAggregateId, "dispatchedAggregateId");
        if (status == Status.DISPATCHED
            && kind == this.dispatchedAggregateKind
            && dispatchedAggregateId.equals(this.dispatchedAggregateId)) {
            return;
        }
        Assert.state(status == Status.REQUESTED,
            "Cannot mark dispatched: replenishment " + id.value() + " is " + status.code());
        Assert.state(matchesTargetService(kind),
            "Dispatched kind " + kind.code() + " does not match target_service "
                + targetService.code() + " on replenishment " + id.value());
        this.status = Status.DISPATCHED;
        this.dispatchedAggregateKind = kind;
        this.dispatchedAggregateId = dispatchedAggregateId;
        this.dispatchedAt = Instant.now();
    }

    /**
     * Stamp the PO that fulfils a purchasing-routed replenishment so the
     * eventual {@code GoodsReceived} can resolve back to this request via
     * {@code linked_purchase_order_id}. Only valid when the request is
     * dispatched to a {@link DispatchedAggregateKind#PURCHASE_REQUISITION};
     * the manufacturing path resolves directly off
     * {@code dispatched_aggregate_id = workOrderId} and doesn't need this link.
     *
     * <p>Idempotent when the same PO arrives twice (e.g. redelivery, or both
     * the {@code PurchaseOrderCreated} race-resolution path + the dispatch
     * race-resolution path stamping it).
     */
    public void linkPurchaseOrder(UUID purchaseOrderId) {
        Assert.notNull(purchaseOrderId, "purchaseOrderId");
        if (purchaseOrderId.equals(this.linkedPurchaseOrderId)) {
            return;
        }
        Assert.state(this.linkedPurchaseOrderId == null,
            "Replenishment " + id.value() + " is already linked to purchase_order="
                + this.linkedPurchaseOrderId);
        Assert.state(dispatchedAggregateKind == DispatchedAggregateKind.PURCHASE_REQUISITION,
            "linkPurchaseOrder is only valid for purchase-requisition-dispatched replenishments; "
                + "this one is " + (dispatchedAggregateKind == null ? "not dispatched" : dispatchedAggregateKind.code()));
        this.linkedPurchaseOrderId = purchaseOrderId;
    }

    /**
     * Mark this request as fulfilled. Emits {@link ReplenishmentFulfilled}.
     * Idempotent against the already-fulfilled state (a redelivered completion
     * event no-ops). Rejects transitions from any non-{@code DISPATCHED} state.
     */
    public void markFulfilled() {
        if (status == Status.FULFILLED) {
            return;
        }
        Assert.state(status == Status.DISPATCHED,
            "Cannot mark fulfilled: replenishment " + id.value() + " is " + status.code()
                + " — only DISPATCHED requests can fulfil");
        this.status = Status.FULFILLED;
        this.fulfilledAt = Instant.now();
        // Payload denormalises productId + propagates the sales-order
        // back-reference (header + line) so consumers (notably sales' fulfilment-
        // saga fan-in handler) can route the event without a join back to
        // inventory.replenishment_request. pegged = ORDER_PEGGED: the
        // output was reserved for the SO line on completion, so sales ships
        // without a re-reservation retry.
        pendingEvents.add(new ReplenishmentFulfilled(
            UUID.randomUUID(),
            id.value(),
            productId,
            sourceSalesOrderHeaderId,
            sourceSalesOrderLineId,
            reason == Reason.ORDER_PEGGED,
            Instant.now()
        ));
    }

    /**
     * Cancel this request — the downstream service couldn't source it (no active
     * BOM / no supplier / discontinued) or inventory classified the SKU as
     * unsourceable. Emits {@link ReplenishmentCancelled} carrying the sales-order
     * back-reference (non-null only for {@code sales_order_shortage}) so sales'
     * fan-in can reject the originating order. Idempotent against the
     * already-cancelled state. Valid only from {@code REQUESTED} or
     * {@code DISPATCHED}; a fulfilled request can't be cancelled.
     */
    public void markCancelled(String reason) {
        if (status == Status.CANCELLED) {
            return;
        }
        Assert.state(status == Status.REQUESTED || status == Status.DISPATCHED,
            "Cannot cancel: replenishment " + id.value() + " is " + status.code()
                + " — only REQUESTED or DISPATCHED requests can be cancelled");
        this.status = Status.CANCELLED;
        this.cancelledAt = Instant.now();
        pendingEvents.add(new ReplenishmentCancelled(
            UUID.randomUUID(),
            id.value(),
            productId,
            sourceSalesOrderHeaderId,
            sourceSalesOrderLineId,
            reason,
            Instant.now()
        ));
    }

    private boolean matchesTargetService(DispatchedAggregateKind kind) {
        return switch (kind) {
            case WORK_ORDER -> targetService == TargetService.MANUFACTURING;
            case PURCHASE_REQUISITION -> targetService == TargetService.PURCHASING;
        };
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    public ReplenishmentRequestId id()                  { return id; }
    public UUID productId()                             { return productId; }
    public UUID warehouseId()                           { return warehouseId; }
    public BigDecimal requestedQuantity()               { return requestedQuantity; }
    public TargetService targetService()                { return targetService; }
    public Reason reason()                              { return reason; }
    public UUID sourceSalesOrderHeaderId()              { return sourceSalesOrderHeaderId; }
    public UUID sourceSalesOrderLineId()                { return sourceSalesOrderLineId; }
    public Status status()                              { return status; }
    public DispatchedAggregateKind dispatchedAggregateKind() { return dispatchedAggregateKind; }
    public UUID dispatchedAggregateId()                 { return dispatchedAggregateId; }
    public UUID linkedPurchaseOrderId()                 { return linkedPurchaseOrderId; }
    public Instant dispatchedAt()                       { return dispatchedAt; }
    public Instant fulfilledAt()                        { return fulfilledAt; }
    public Instant cancelledAt()                        { return cancelledAt; }
    public long version()                               { return version; }
}
