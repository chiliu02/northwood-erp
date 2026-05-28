package com.northwood.inventory.domain.replenishment;

import com.northwood.inventory.domain.InventoryAggregateTypes;
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
 * §2.35 Slice B: aggregate root for an inventory-orchestrated replenishment.
 * One row per request; lifecycle is linear:
 *
 * <pre>
 *   requested  ──(Slice E: ReplenishmentDispatched)──▶  dispatched
 *      │                                                    │
 *      │                                                    ▼
 *      │                                  (Slice E: WorkOrderManufacturingCompleted
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
 * <p>Status transitions: this slice ships {@code requested} and {@code cancelled}.
 * Slice E adds {@code markDispatched} + {@code linkPurchaseOrder} +
 * {@code markFulfilled} when the close-the-loop handlers land.
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

        private final String dbValue;

        Status(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static Status fromDb(String value) {
            for (Status s : values()) {
                if (s.dbValue.equals(value)) return s;
            }
            throw Assert.unknownValue("replenishment request status", value);
        }
    }

    /**
     * Which downstream service handles the request. Derived by the detection
     * service from the SKU's make-vs-buy classification (snapshotted into
     * inventory by Slice A's {@code ProductReplenishmentProjection}).
     */
    public enum TargetService {
        MANUFACTURING("manufacturing"),
        PURCHASING("purchasing");

        private final String dbValue;

        TargetService(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static TargetService fromDb(String value) {
            for (TargetService t : values()) {
                if (t.dbValue.equals(value)) return t;
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
        /** Slice C bridge — WO release found short raw materials, routed via inventory. */
        WORK_ORDER_SHORTAGE("work_order_shortage");

        private final String dbValue;

        Reason(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static Reason fromDb(String value) {
            for (Reason r : values()) {
                if (r.dbValue.equals(value)) return r;
            }
            throw Assert.unknownValue("replenishment request reason", value);
        }
    }

    /**
     * Which kind of downstream aggregate is fulfilling this replenishment.
     * Populated by {@link #markDispatched(DispatchedAggregateKind, UUID)} when
     * Slice E's close-the-loop handler receives the corresponding
     * {@code ReplenishmentDispatched} event.
     */
    public enum DispatchedAggregateKind {
        WORK_ORDER("work_order"),
        PURCHASE_REQUISITION("purchase_requisition");

        private final String dbValue;

        DispatchedAggregateKind(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static DispatchedAggregateKind fromDb(String value) {
            for (DispatchedAggregateKind k : values()) {
                if (k.dbValue.equals(value)) return k;
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
    private Status status;
    private DispatchedAggregateKind dispatchedAggregateKind;
    private UUID dispatchedAggregateId;
    private UUID linkedPurchaseOrderId;
    private Instant dispatchedAt;
    private Instant fulfilledAt;
    private final long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /** Factory: raise a new request. Emits {@link ReplenishmentRequested}. */
    public static ReplenishmentRequest request(
        UUID productId,
        UUID warehouseId,
        BigDecimal requestedQuantity,
        TargetService targetService,
        Reason reason
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
            targetService, reason, Status.REQUESTED,
            null, null, null, null, null,
            0L
        );
        r.pendingEvents.add(new ReplenishmentRequested(
            UUID.randomUUID(),
            id.value(),
            productId,
            warehouseId,
            requestedQuantity,
            targetService.dbValue(),
            reason.dbValue(),
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
        Status status,
        DispatchedAggregateKind dispatchedAggregateKind,
        UUID dispatchedAggregateId,
        UUID linkedPurchaseOrderId,
        Instant dispatchedAt,
        Instant fulfilledAt,
        long version
    ) {
        return new ReplenishmentRequest(
            id, productId, warehouseId, requestedQuantity,
            targetService, reason, status,
            dispatchedAggregateKind, dispatchedAggregateId, linkedPurchaseOrderId,
            dispatchedAt, fulfilledAt,
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
        Status status,
        DispatchedAggregateKind dispatchedAggregateKind,
        UUID dispatchedAggregateId,
        UUID linkedPurchaseOrderId,
        Instant dispatchedAt,
        Instant fulfilledAt,
        long version
    ) {
        this.id = id;
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.requestedQuantity = requestedQuantity;
        this.targetService = targetService;
        this.reason = reason;
        this.status = status;
        this.dispatchedAggregateKind = dispatchedAggregateKind;
        this.dispatchedAggregateId = dispatchedAggregateId;
        this.linkedPurchaseOrderId = linkedPurchaseOrderId;
        this.dispatchedAt = dispatchedAt;
        this.fulfilledAt = fulfilledAt;
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
            "Cannot mark dispatched: replenishment " + id.value() + " is " + status.dbValue());
        Assert.state(matchesTargetService(kind),
            "Dispatched kind " + kind.dbValue() + " does not match target_service "
                + targetService.dbValue() + " on replenishment " + id.value());
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
                + "this one is " + (dispatchedAggregateKind == null ? "not dispatched" : dispatchedAggregateKind.dbValue()));
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
            "Cannot mark fulfilled: replenishment " + id.value() + " is " + status.dbValue()
                + " — only DISPATCHED requests can fulfil");
        this.status = Status.FULFILLED;
        this.fulfilledAt = Instant.now();
        pendingEvents.add(new ReplenishmentFulfilled(
            UUID.randomUUID(),
            id.value(),
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
    public Status status()                              { return status; }
    public DispatchedAggregateKind dispatchedAggregateKind() { return dispatchedAggregateKind; }
    public UUID dispatchedAggregateId()                 { return dispatchedAggregateId; }
    public UUID linkedPurchaseOrderId()                 { return linkedPurchaseOrderId; }
    public Instant dispatchedAt()                       { return dispatchedAt; }
    public Instant fulfilledAt()                        { return fulfilledAt; }
    public long version()                               { return version; }
}
