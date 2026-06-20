package com.northwood.inventory.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inventory's ack to {@code sales.SalesOrderCancellationRequested}: the
 * reservation against this sales order (if any) has been released, with the
 * corresponding {@code stock_balance.reserved_quantity} bumps rolled back.
 *
 * <p>Idempotent against zero reservations: if the cancel arrived before the
 * stock reservation was even created (or the reservation was already
 * released), the ack still fires with {@code reservationsReleased = 0} so the
 * sales fulfilment saga can advance to {@code compensated} without a special
 * case.
 *
 * <p><b>Compensation legs.</b> {@code legs} enumerates the order-pegged supply
 * whose committed downstream artifact must still be withdrawn — a sent purchase
 * order ({@code targetService = purchasing}) or a released work order
 * ({@code targetService = manufacturing}) raised for a {@code to_order} short
 * line that has not yet shipped. Inventory fans out one
 * {@code OrderPeggedSupplyCancellationRequested} per leg (it owns the
 * peg→PO/WO mapping) in the same transaction, and reports the leg set here so the
 * sales saga knows how many {@code *CancellationApplied} acks to wait for before
 * declaring the order {@code compensated} / {@code compensation_failed}. <b>Empty
 * in the common case</b> (nothing order-pegged, or the peg already fulfilled) →
 * sales compensates straight away, exactly as before. The reservation release is
 * <em>not</em> a leg — this very ack is its completion.
 *
 * <p><b>Java name vs wire format.</b> The class is prefixed with
 * {@code Inventory} only to disambiguate from manufacturing's equivalent ack
 * (Java's flat namespace can't carry both as {@code SalesOrderCancellationApplied}
 * without forcing FQNs at every dual-import site). The wire format
 * {@code "inventory.SalesOrderCancellationApplied"} is unchanged — the
 * {@code inventory.} prefix already conveys the source service, so doubling it
 * in the event name would read awkwardly to consumers.
 */
public record InventorySalesOrderCancellationApplied(
    UUID eventId,
    UUID aggregateId,         // sales_order_header_id (saga finds saga by this)
    int reservationsReleased,
    List<CompensationLeg> legs,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "inventory.SalesOrderCancellationApplied";

    /**
     * One order-pegged supply leg awaiting withdrawal. {@code targetService} is
     * the consumer that must cancel its aggregate ({@code purchasing} for a sent
     * PO, {@code manufacturing} for a released work order — the
     * {@code replenishment_request.target_service} vocabulary); the sales saga
     * forms the leg id it drains as {@code targetService + ":" + salesOrderLineId}.
     */
    public record CompensationLeg(
        String targetService,
        UUID salesOrderLineId
    ) {}

    @Override public String eventType() { return EVENT_TYPE; }
}
