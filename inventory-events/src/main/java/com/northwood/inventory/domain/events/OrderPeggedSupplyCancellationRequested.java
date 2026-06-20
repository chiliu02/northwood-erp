package com.northwood.inventory.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Inventory asks the supplying service to <b>withdraw the committed order-pegged
 * supply</b> for a cancelled {@code to_order} sales-order line — a sent purchase
 * order ({@code targetService = purchasing}) or a released work order
 * ({@code targetService = manufacturing}) raised for a short line that has not yet
 * shipped. Emitted by {@code StockReservationService.unpegForSalesOrder} once per
 * {@code DISPATCHED} order-pegged replenishment, in the same transaction as the
 * {@code inventory.SalesOrderCancellationApplied} ack that enumerates the same legs
 * back to the sales saga.
 *
 * <p>Inventory owns the peg → PO/WO mapping ({@code replenishment_request}
 * .{@code dispatched_aggregate_kind} / {@code dispatched_aggregate_id} /
 * {@code linked_purchase_order_id}), so it is the natural emitter of this request —
 * the same way {@code inventory.ReplenishmentRequested} is consumed by purchasing
 * as "create a PO". The consumer cancels its own aggregate and replies with a
 * {@code *CancellationApplied} ack addressed to the sales saga
 * ({@code sourceSalesOrderHeaderId}), which drains the matching compensation leg.
 *
 * <p>Partition key is {@code aggregateId} (the replenishment_request id) — per-leg
 * ordering. {@code targetAggregateId} is the id of the artifact to cancel: the
 * purchase order ({@code linked_purchase_order_id}) for purchasing, the work order
 * ({@code dispatched_aggregate_id}) for manufacturing.
 */
public record OrderPeggedSupplyCancellationRequested(
    UUID eventId,
    UUID aggregateId,                 // replenishment_request_id (partition key)
    String targetService,             // 'purchasing' | 'manufacturing'
    String dispatchedAggregateKind,   // 'purchase_requisition' | 'work_order'
    UUID targetAggregateId,           // PO id (purchasing) | WO id (manufacturing) to cancel
    UUID sourceSalesOrderHeaderId,    // the sales saga to ack back to
    UUID sourceSalesOrderLineId,      // forms the saga leg id '<targetService>:<lineId>'
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "inventory.OrderPeggedSupplyCancellationRequested";

    /** {@code targetService} value routing a leg to purchasing (a PO to withdraw). */
    public static final String TARGET_SERVICE_PURCHASING = "purchasing";
    /** {@code targetService} value routing a leg to manufacturing (a work order to withdraw). */
    public static final String TARGET_SERVICE_MANUFACTURING = "manufacturing";
    /** {@code dispatchedAggregateKind} for a purchasing-routed leg. */
    public static final String DISPATCHED_KIND_PURCHASE_REQUISITION = "purchase_requisition";
    /** {@code dispatchedAggregateKind} for a manufacturing-routed leg. */
    public static final String DISPATCHED_KIND_WORK_ORDER = "work_order";

    @Override public String eventType() { return EVENT_TYPE; }
}
