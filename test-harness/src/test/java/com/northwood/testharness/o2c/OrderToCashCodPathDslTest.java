package com.northwood.testharness.o2c;

import static com.northwood.testharness.dsl.Dsl.a_commercial_invoice;
import static com.northwood.testharness.dsl.Dsl.a_customer;
import static com.northwood.testharness.dsl.Dsl.a_customer_payment;
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
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.SUPPLY_SECURED;

import com.northwood.finance.domain.Payment;
import com.northwood.finance.domain.events.CustomerInvoiceCreated;
import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.sales.domain.events.SalesOrderShipped;
import org.junit.jupiter.api.Test;

/**
 * Cash-on-delivery order-to-cash, ported from {@code OrderToCashCodPathTest}
 * (REQ-SAL-020 cash-on-delivery terms; REQ-FIN-024/025 invoice + payment posting).
 *
 * <p>The distinguishing business fact vs the happy path: COD settles at the
 * goods-delivered moment with <em>no operator payment step</em>. The scenario
 * has no {@code pays(...)} {@code when} — shipping the order is the last trigger,
 * and {@code is_completed()} proves finance auto-invoiced and auto-recorded the
 * full cash payment from the single {@code SalesOrderShipped} event. The cash
 * method is asserted explicitly to pin the COD branch.
 */
class OrderToCashCodPathDslTest {

    @Test
    void cod_order_completes_and_auto_settles_at_shipment() {
        scenario("cash-on-delivery order: ships, then auto-invoices and auto-settles in cash")

            // ── guard: a priced product, in stock, for a known customer ──
            .given(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("FG-001", "Finished Good 1").pricedAt(money(100)))
            .and(stock_on_hand("FG-001", qty(50)).at(MAIN))

            // ── trigger: customer places a COD order for 3 units ──
            .when(customer("CUST-001").places_order("SO-COD-1").cash_on_delivery()
                .line("FG-001", qty(3)))
            .then(order("SO-COD-1").reaches(SUPPLY_SECURED))

            // ── trigger: the warehouse ships all 3 units — the last operator action ──
            .when(warehouse(MAIN).ships("SO-COD-1")
                .line("FG-001", qty(3)).at_unit_cost(money(60)))
            // ── outcome: the order completes with no payment step; cash auto-recorded ──
            .then(order("SO-COD-1").is_completed())
            .and(a_commercial_invoice().for_order("SO-COD-1").totalling(money(300)))
            .and(a_customer_payment().byMethod(Payment.Method.CASH).wasRecorded())
            .and(events_published(
                SalesOrderPlaced.EVENT_TYPE,
                StockReserved.EVENT_TYPE,
                ShipmentPosted.EVENT_TYPE,
                SalesOrderShipped.EVENT_TYPE,
                CustomerInvoiceCreated.EVENT_TYPE,
                CustomerPaymentReceived.EVENT_TYPE));
    }
}
