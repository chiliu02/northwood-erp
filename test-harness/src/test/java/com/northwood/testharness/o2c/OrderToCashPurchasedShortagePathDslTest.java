package com.northwood.testharness.o2c;

import static com.northwood.testharness.dsl.Dsl.a_customer;
import static com.northwood.testharness.dsl.Dsl.a_product;
import static com.northwood.testharness.dsl.Dsl.a_replenishment_request;
import static com.northwood.testharness.dsl.Dsl.customer;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.goods_received_for;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.order;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.READY_TO_SHIP;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STOCK_RESERVATION_INCOMPLETE;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.sales.domain.SalesOrder;
import org.junit.jupiter.api.Test;

/**
 * Sales-shortage on a purchased-only SKU, ported from
 * {@code OrderToCashPurchasedShortagePathTest} (REQ-XBC-030, REQ-INV-020/091). A
 * sales order for a purchased product with no stock fails reservation; inventory
 * (the sole make-vs-buy decision point) raises a {@code sales_order_shortage}
 * replenishment routed to purchasing; once the bought stock lands the order
 * re-reserves and ships — manufacturing never involved.
 *
 * <p><b>More faithful than its imperative twin.</b> The hand-written test shortcuts
 * the fulfilment via direct {@code markDispatched} / {@code markFulfilled} calls;
 * this port drives the real purchasing flow (PR → PO) and a real
 * {@code GoodsReceiptService} receipt, which tops up the pool and fulfils the
 * replenishment.
 */
class OrderToCashPurchasedShortagePathDslTest {

    @Test
    void purchased_only_shortage_replenishes_then_reserves_and_ships() {
        scenario("a sales order short on a purchased product replenishes via a PO, then ships")

            // ── guard: a sold, purchased product (supplier price 12); NO stock ──
            .given(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("WIDGET-001", "Widget").pricedAt(money(12)).purchasedFrom(money(12)))

            // ── trigger: place an order for 5 — reservation fails, shortage routed to purchasing ──
            .when(customer("CUST-001").places_order("SO-PSH-1").line("WIDGET-001", qty(5)))
            .then(order("SO-PSH-1").reaches(STOCK_RESERVATION_INCOMPLETE))
            .and(a_replenishment_request("WIDGET-001")
                .routedTo(ReplenishmentRequest.TargetService.PURCHASING)
                .because(ReplenishmentRequest.Reason.SALES_ORDER_SHORTAGE)
                .ofQuantity(qty(5))
                .forOrder("SO-PSH-1")
                .reaches(ReplenishmentRequest.Status.DISPATCHED))

            // ── trigger: goods are received against the PO (for real) ──
            .when(goods_received_for("WIDGET-001"))
            // ── outcome: the pool tops up, the order re-reserves and reaches ready-to-ship ──
            .then(order("SO-PSH-1").reaches(READY_TO_SHIP))
            .and(order("SO-PSH-1").has_status(SalesOrder.Status.RESERVED))
            .and(events_published(
                ReplenishmentRequested.EVENT_TYPE,
                ReplenishmentFulfilled.EVENT_TYPE));
    }
}
