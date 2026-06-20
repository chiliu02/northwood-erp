package com.northwood.testharness.o2c;

import static com.northwood.testharness.dsl.Dsl.a_customer;
import static com.northwood.testharness.dsl.Dsl.a_product;
import static com.northwood.testharness.dsl.Dsl.a_replenishment_request;
import static com.northwood.testharness.dsl.Dsl.customer;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.order;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPENSATED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STOCK_RESERVATION_INCOMPLETE;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.events.InventorySalesOrderCancellationApplied;
import com.northwood.inventory.domain.events.OrderPeggedSupplyCancellationRequested;
import com.northwood.purchasing.domain.events.PurchaseOrderCancellationApplied;
import com.northwood.purchasing.domain.events.PurchaseOrderCancelled;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.sales.domain.events.SalesOrderCompensated;
import org.junit.jupiter.api.Test;

/**
 * Multi-leg compensation — the purchasing leg. A {@code to_order} purchased SKU
 * raises a dedicated order-pegged purchase order; cancelling the order <em>before
 * goods receipt</em> must withdraw that committed PO, not orphan it. This is the
 * reachable, demonstrated compensation the design note calls for: the sales saga
 * fans the undo out to purchasing (via inventory, which owns the peg→PO map), parks
 * in {@code compensating}, and reaches {@code compensated} only once purchasing acks
 * the PO withdrawal.
 *
 * <p>The full chain runs under the real services + saga manager + inbox handlers:
 * {@code SalesOrderCancellationRequested → InventorySalesOrderCancellationApplied}
 * (enumerating the purchasing leg) {@code → OrderPeggedSupplyCancellationRequested →}
 * {@code PurchaseOrder.compensateCancel} {@code → PurchaseOrderCancellationApplied →}
 * the saga drains its single leg and emits {@code SalesOrderCompensated}.
 *
 * <p><b>Reject parity is inherent, not separately tested.</b> The internal reject
 * path fires precisely when a line's replenishment is <em>unsourceable</em> (the
 * request is cancelled, never dispatched), so a rejected line can never carry a
 * {@code DISPATCHED} PO leg — reject takes the zero-leg path straight to
 * {@code compensated}, already covered by the saga-manager unit tests.
 */
class OrderToCashCompensatePurchasedLegDslTest {

    @Test
    void cancelling_a_to_order_line_withdraws_the_pegged_po_and_compensates() {
        scenario("cancel a buy-to-order line before goods receipt: the pegged PO is withdrawn, the order compensates")

            // ── guard: a sold, buy-to-order product (supplier price 700); NO stock ──
            .given(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("FG-CARPET-001", "Custom-design Carpet")
                .pricedAt(money(1200)).purchasedToOrder(money(700)))

            // ── trigger: place an order — the line parks awaiting a dedicated pegged PO ──
            .when(customer("CUST-001").places_order("SO-CMP-1").line("FG-CARPET-001", qty(1)))
            .then(order("SO-CMP-1").reaches(STOCK_RESERVATION_INCOMPLETE))
            .and(a_replenishment_request("FG-CARPET-001")
                .routedTo(ReplenishmentRequest.TargetService.PURCHASING)
                .because(ReplenishmentRequest.Reason.ORDER_PEGGED)
                .ofQuantity(qty(1))
                .forOrder("SO-CMP-1")
                .reaches(ReplenishmentRequest.Status.DISPATCHED))

            // ── trigger: cancel before goods receipt — the PO is still committed ──
            .when(customer("CUST-001").cancels("SO-CMP-1").because("customer changed mind"))

            // ── outcome: the purchasing leg withdraws the PO, the saga compensates ──
            .then(order("SO-CMP-1").reaches(COMPENSATED))
            .and(order("SO-CMP-1").has_status(SalesOrder.Status.CANCELLED))
            .and(events_published(
                SalesOrderCancellationRequested.EVENT_TYPE,
                InventorySalesOrderCancellationApplied.EVENT_TYPE,
                OrderPeggedSupplyCancellationRequested.EVENT_TYPE,
                PurchaseOrderCancellationApplied.EVENT_TYPE,
                PurchaseOrderCancelled.EVENT_TYPE,
                SalesOrderCompensated.EVENT_TYPE));
    }
}
