package com.northwood.testharness.o2c;

import static com.northwood.testharness.dsl.Dsl.a_customer;
import static com.northwood.testharness.dsl.Dsl.a_product;
import static com.northwood.testharness.dsl.Dsl.a_replenishment_request;
import static com.northwood.testharness.dsl.Dsl.amend;
import static com.northwood.testharness.dsl.Dsl.customer;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.order;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Dsl.stock_on_hand;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.READY_TO_SHIP;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STOCK_RESERVATION_INCOMPLETE;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.events.SalesOrderLineReservationChanged;
import org.junit.jupiter.api.Test;

/**
 * Sales-order line amendment, ported from {@code OrderToCashLineAmendmentTest}
 * (REQ-SAL-010, REQ-INV-020). A line added to an already-reserved order is
 * reserved incrementally (the order stays ready-to-ship); a short line removed
 * from a shortage-parked order releases its reservation, cancels its in-flight
 * replenishment, and un-parks the saga to ready-to-ship.
 */
class OrderToCashLineAmendmentDslTest {

    @Test
    void add_line_to_reserved_order_reserves_incrementally_and_stays_ready_to_ship() {
        scenario("a line added to a reserved order is reserved incrementally")
            .given(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("FG-A", "Good A").pricedAt(money(100)))
            .and(a_product("FG-B", "Good B").pricedAt(money(40)))
            .and(stock_on_hand("FG-A", qty(50)).at(MAIN))
            .and(stock_on_hand("FG-B", qty(50)).at(MAIN))

            .when(customer("CUST-001").places_order("SO-AMEND-1").line("FG-A", qty(3)))
            .then(order("SO-AMEND-1").reaches(READY_TO_SHIP))

            // ── trigger: add a second, fully-reservable line ──
            .when(amend("SO-AMEND-1").add_line("FG-B", qty(2)))
            // ── outcome: reserved incrementally; the order stays ready-to-ship ──
            .then(order("SO-AMEND-1").reaches(READY_TO_SHIP))
            .and(events_published(SalesOrderLineReservationChanged.EVENT_TYPE));
    }

    @Test
    void remove_short_line_cancels_replenishment_and_unparks_to_ready_to_ship() {
        scenario("removing a short line cancels its replenishment and un-parks the order")
            .given(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("FG-A", "Good A").pricedAt(money(100)))
            // FG-B is purchased with no stock — its shortage raises a routed replenishment.
            .and(a_product("FG-B", "Good B").pricedAt(money(40)).purchasedFrom(money(20)))
            .and(stock_on_hand("FG-A", qty(50)).at(MAIN))

            .when(customer("CUST-001").places_order("SO-AMEND-2")
                .line("FG-A", qty(3)).line("FG-B", qty(2)))
            // ── FG-B is short → the order parks awaiting its replenishment ──
            .then(order("SO-AMEND-2").reaches(STOCK_RESERVATION_INCOMPLETE))
            .and(a_replenishment_request("FG-B")
                .routedTo(ReplenishmentRequest.TargetService.PURCHASING)
                .reaches(ReplenishmentRequest.Status.DISPATCHED))

            // ── trigger: remove the short line ──
            .when(amend("SO-AMEND-2").remove_line("FG-B"))
            // ── outcome: its replenishment is cancelled and the order un-parks ──
            .then(order("SO-AMEND-2").reaches(READY_TO_SHIP))
            .and(a_replenishment_request("FG-B").reaches(ReplenishmentRequest.Status.CANCELLED));
    }
}
