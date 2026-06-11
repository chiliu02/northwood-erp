package com.northwood.testharness.o2c;

import static com.northwood.testharness.dsl.Dsl.a_bom;
import static com.northwood.testharness.dsl.Dsl.a_customer;
import static com.northwood.testharness.dsl.Dsl.a_product;
import static com.northwood.testharness.dsl.Dsl.a_raw_material;
import static com.northwood.testharness.dsl.Dsl.a_replenishment_request;
import static com.northwood.testharness.dsl.Dsl.a_routing;
import static com.northwood.testharness.dsl.Dsl.a_stock_balance;
import static com.northwood.testharness.dsl.Dsl.customer;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.events_published_count;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.order;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Dsl.stock_on_hand;
import static com.northwood.testharness.dsl.Dsl.work_order_for;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.SUPPLY_SECURED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STOCK_RESERVATION_INCOMPLETE;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.manufacturing.domain.events.WorkOrderCreated;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.sales.domain.events.StockReservationRequested;
import org.junit.jupiter.api.Test;

/**
 * Make-to-order path, ported from {@code OrderToCashMakeToOrderPathTest}
 * (REQ-INV-093, REQ-PROD-022). A sales order for a {@code to_order} manufactured
 * SKU raises dedicated order-pegged supply routed to manufacturing rather than
 * drawing from / building to the shared pool; on the work order's completion the
 * output is reserved (pegged) to the originating line atomically with the stock
 * credit, so the saga ships straight off the peg with no re-reservation retry and
 * the pegged stock never enters free ATP.
 *
 * <p>Like the manufactured-replenishment port, this drives the <em>real</em> work
 * order to completion (the imperative twin forges the completion event).
 */
class OrderToCashMakeToOrderPathDslTest {

    @Test
    void to_order_manufactured_sku_pegs_on_completion_and_ships_without_retry() {
        scenario("a make-to-order product raises a pegged work order and ships off the peg")

            // ── guard: a sold, make-to-order FG (1 raw/unit, single-op routing); raws on hand, NO FG stock ──
            .given(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("FG-MTO-001", "Made-to-order FG").pricedAt(money(250)).manufacturedToOrder())
            .and(a_raw_material("RM-001", "Raw Material 1"))
            .and(a_bom("FG-MTO-001").withRawLine("RM-001", qty(1)))
            .and(a_routing("FG-MTO-001").singleOp())
            .and(stock_on_hand("RM-001", qty(100)).at(MAIN))

            // ── trigger: place an order for 3 units of the to-order FG ──
            .when(customer("CUST-001").places_order("SO-MTO-1").line("FG-MTO-001", qty(3)))
            // ── outcome: the line never touches the pool — it parks awaiting dedicated supply ──
            .then(order("SO-MTO-1").reaches(STOCK_RESERVATION_INCOMPLETE))
            .and(a_replenishment_request("FG-MTO-001")
                .routedTo(ReplenishmentRequest.TargetService.MANUFACTURING)
                .because(ReplenishmentRequest.Reason.ORDER_PEGGED)
                .ofQuantity(qty(3))
                .forOrder("SO-MTO-1")
                .reaches(ReplenishmentRequest.Status.DISPATCHED))

            // ── trigger: the work order is built and completed (for real) ──
            .when(work_order_for("FG-MTO-001").completes_manufacturing())
            // ── outcome: ready to ship off the peg; pegged stock has 0 available; no second reservation ──
            .then(order("SO-MTO-1").reaches(SUPPLY_SECURED))
            .and(a_stock_balance("FG-MTO-001").shows(qty(3), qty(3), qty(0)))
            .and(events_published_count(StockReservationRequested.EVENT_TYPE, 1))
            .and(events_published(
                ReplenishmentRequested.EVENT_TYPE,
                WorkOrderCreated.EVENT_TYPE,
                WorkOrderManufacturingCompleted.EVENT_TYPE,
                ReplenishmentFulfilled.EVENT_TYPE));
    }
}
