package com.northwood.testharness.dsl.o2c;

import static com.northwood.testharness.dsl.Dsl.a_commercial_invoice;
import static com.northwood.testharness.dsl.Dsl.a_customer;
import static com.northwood.testharness.dsl.Dsl.a_product;
import static com.northwood.testharness.dsl.Dsl.customer;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.order;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Dsl.stock_on_hand;
import static com.northwood.testharness.dsl.Dsl.warehouse;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.INVOICE_CREATED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.READY_TO_SHIP;

import com.northwood.finance.domain.events.CustomerInvoiceCreated;
import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.sales.domain.events.SalesOrderReadyToShip;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.sales.domain.events.StockReservationRequested;
import org.junit.jupiter.api.Test;

/**
 * Slice-2 of the acceptance-test DSL (see {@code docs/test-harness-dsl.md} §7) — the o2c
 * happy path expressed entirely in the ubiquitous language: orders, shipments,
 * invoices, payments. No {@code drain}, no {@code advanceSagaWorker}, no UUIDs,
 * no positional command records.
 *
 * <p>Same behaviour as {@code o2c/OrderToCashHappyPathTest} and
 * {@code WorldTest} — the real services, saga worker,
 * inbox handlers, and Jackson 3 serde run underneath every step — but every
 * line here is either the requirement ({@code given/when/then}) or a real
 * constant. Compare to the hand-written test to see the noise that's gone.
 */
class OrderToCashHappyPathDslTest {

    @Test
    void in_stock_order_ships_invoices_and_settles_in_full() {
        scenario("in-stock order: ships, invoices, and settles in full")

            // ── guard: a priced product, in stock, for a known customer ──
            .given(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("FG-001", "Finished Good 1").pricedAt(money(100)))
            .and(stock_on_hand("FG-001", qty(50)).at(MAIN))

            // ── trigger: customer places an order for 3 units ──
            .when(customer("CUST-001").places_order("SO-9001")
                .line("FG-001", qty(3)))
            // ── outcome: full reservation shortcuts straight to ready-to-ship ──
            .then(order("SO-9001").reaches(READY_TO_SHIP))
            .and(order("SO-9001").has_status(SalesOrder.Status.RESERVED))

            // ── trigger: the warehouse ships all 3 units ──
            .when(warehouse(MAIN).ships("SO-9001")
                .line("FG-001", qty(3)).at_unit_cost(money(60)))
            // ── outcome: a commercial invoice for 3 × 100 is raised ──
            .then(order("SO-9001").reaches(INVOICE_CREATED))
            .and(a_commercial_invoice().for_order("SO-9001").totalling(money(300)))

            // ── trigger: customer settles the invoice in full ──
            .when(customer("CUST-001").pays(money(300)).against("SO-9001"))
            // ── outcome: order completes; the expected events crossed services ──
            .then(order("SO-9001").is_completed())
            .and(events_published(
                SalesOrderPlaced.EVENT_TYPE,
                StockReservationRequested.EVENT_TYPE,
                StockReserved.EVENT_TYPE,
                SalesOrderReadyToShip.EVENT_TYPE,
                ShipmentPosted.EVENT_TYPE,
                SalesOrderShipped.EVENT_TYPE,
                CustomerInvoiceCreated.EVENT_TYPE,
                CustomerPaymentReceived.EVENT_TYPE));
    }
}
