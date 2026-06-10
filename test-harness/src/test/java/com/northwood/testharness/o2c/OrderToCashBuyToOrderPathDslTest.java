package com.northwood.testharness.o2c;

import static com.northwood.testharness.dsl.Dsl.a_customer;
import static com.northwood.testharness.dsl.Dsl.a_product;
import static com.northwood.testharness.dsl.Dsl.a_replenishment_request;
import static com.northwood.testharness.dsl.Dsl.a_stock_balance;
import static com.northwood.testharness.dsl.Dsl.customer;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.events_published_count;
import static com.northwood.testharness.dsl.Dsl.goods_received_for;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.order;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.READY_TO_SHIP;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STOCK_RESERVATION_INCOMPLETE;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.sales.domain.events.StockReservationRequested;
import org.junit.jupiter.api.Test;

/**
 * Buy-to-order path, ported from {@code OrderToCashBuyToOrderPathTest}
 * (REQ-INV-093, REQ-PROD-022) — the purchasing mirror of make-to-order. A sales
 * order for a {@code to_order} purchased SKU raises a dedicated order-pegged
 * purchase order; on goods receipt the bought stock is reserved (pegged) to the
 * originating line atomically with the on-hand credit, so the saga ships off the
 * peg with no re-reservation retry and the pegged stock is excluded from ATP.
 *
 * <p>Drives the real {@code GoodsReceiptService} so the buy-side peg-on-receipt
 * is exercised end-to-end.
 */
class OrderToCashBuyToOrderPathDslTest {

    @Test
    void to_order_purchased_sku_pegs_on_goods_receipt_and_ships_without_retry() {
        scenario("a buy-to-order product raises a pegged purchase order and ships off the peg")

            // ── guard: a sold, buy-to-order product (supplier price 700); NO stock ──
            .given(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("FG-CARPET-001", "Custom-design Carpet")
                .pricedAt(money(1200)).purchasedToOrder(money(700)))

            // ── trigger: place an order for 1 unit of the buy-to-order product ──
            .when(customer("CUST-001").places_order("SO-BTO-1").line("FG-CARPET-001", qty(1)))
            // ── outcome: the line parks awaiting dedicated purchased supply ──
            .then(order("SO-BTO-1").reaches(STOCK_RESERVATION_INCOMPLETE))
            .and(a_replenishment_request("FG-CARPET-001")
                .routedTo(ReplenishmentRequest.TargetService.PURCHASING)
                .because(ReplenishmentRequest.Reason.ORDER_PEGGED)
                .ofQuantity(qty(1))
                .forOrder("SO-BTO-1")
                .reaches(ReplenishmentRequest.Status.DISPATCHED))

            // ── trigger: goods are received against the pegged PO (for real) ──
            .when(goods_received_for("FG-CARPET-001"))
            // ── outcome: ready to ship off the peg; pegged stock 0 available; no second reservation ──
            .then(order("SO-BTO-1").reaches(READY_TO_SHIP))
            .and(a_stock_balance("FG-CARPET-001").shows(qty(1), qty(1), qty(0)))
            .and(events_published_count(StockReservationRequested.EVENT_TYPE, 1))
            .and(events_published(
                ReplenishmentRequested.EVENT_TYPE,
                PurchaseOrderCreated.EVENT_TYPE,
                GoodsReceived.EVENT_TYPE,
                ReplenishmentFulfilled.EVENT_TYPE));
    }
}
