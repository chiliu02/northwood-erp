package com.northwood.testharness.o2c;

import static com.northwood.testharness.dsl.Dsl.a_balance_invoice;
import static com.northwood.testharness.dsl.Dsl.a_customer;
import static com.northwood.testharness.dsl.Dsl.a_deposit_invoice;
import static com.northwood.testharness.dsl.Dsl.a_product;
import static com.northwood.testharness.dsl.Dsl.customer;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.order;
import static com.northwood.testharness.dsl.Dsl.percent;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Dsl.stock_on_hand;
import static com.northwood.testharness.dsl.Dsl.warehouse;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.AWAITING_PREPAYMENT;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.SUPPLY_SECURED;

import com.northwood.finance.domain.events.CustomerInvoiceCreated;
import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.sales.domain.events.DepositInvoiceRequested;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.sales.domain.events.SalesOrderUpfrontPaymentSettled;
import org.junit.jupiter.api.Test;

/**
 * Slice-3 of the acceptance-test DSL (see {@code docs/test-harness-dsl.md} §9) — the
 * deposit (part-payment) branch, the richest AR pattern, expressed in the
 * ubiquitous language. Ported from {@code o2c/OrderToCashDepositPathTest} to
 * pressure-test the vocabulary against a branch: two invoices (a deposit at
 * placement, a balance at shipment) and two customer payments, with Customer
 * Deposits (2110) carrying the deposit between them.
 *
 * <p>Order: 3 × $100 = $300, 50% deposit → $150 deposit + $150 balance. The
 * real services, saga worker, inbox handlers, and Jackson 3 serde run under
 * every step; {@code settle()} folds the {@code deposit_invoiced → deposit_paid
 * → SUPPLY_SECURED} worker progression into the single deposit-payment trigger,
 * so the scenario states only the business facts a reviewer checks.
 *
 * <p>Branch vocabulary new in this slice: {@code with_deposit(percent(50))},
 * {@code pays(…).against_deposit_on/against_balance_on(…)}, and
 * {@code a_deposit_invoice()} / {@code a_balance_invoice()}.
 */
class OrderToCashDepositPathDslTest {

    @Test
    void deposit_order_completes_across_two_invoices_and_payments() {
        scenario("deposit order: deposit invoiced + paid, ships, balance invoiced + settled")

            // ── guard: a priced product, in stock, for a known customer ──
            .given(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("FG-001", "Finished Good 1").pricedAt(money(100)))
            .and(stock_on_hand("FG-001", qty(50)).at(MAIN))

            // ── trigger: customer places a 50%-deposit order for 3 units ──
            .when(customer("CUST-001").places_order("SO-DEP-1").with_deposit(percent(50))
                .line("FG-001", qty(3)))
            // ── outcome: a $150 deposit invoice is raised; saga parks at the
            //    up-front-payment gate awaiting the deposit payment ──
            .then(order("SO-DEP-1").reaches(AWAITING_PREPAYMENT))
            .and(a_deposit_invoice().for_order("SO-DEP-1").totalling(money(150)))

            // ── trigger: customer pays the deposit ──
            .when(customer("CUST-001").pays(money(150)).against_deposit_on("SO-DEP-1"))
            // ── outcome: deposit settles, stock reserves, order becomes ready to ship ──
            .then(order("SO-DEP-1").reaches(SUPPLY_SECURED))

            // ── trigger: the warehouse ships all 3 units ──
            .when(warehouse(MAIN).ships("SO-DEP-1")
                .line("FG-001", qty(3)).at_unit_cost(money(60)))
            // ── outcome: the $150 balance invoice is raised (deposit recognised
            //    behind it); the saga holds at supply_secured awaiting balance payment ──
            .then(order("SO-DEP-1").reaches(SUPPLY_SECURED))
            .and(a_balance_invoice().for_order("SO-DEP-1").totalling(money(150)))

            // ── trigger: customer settles the balance in full ──
            .when(customer("CUST-001").pays(money(150)).against_balance_on("SO-DEP-1"))
            // ── outcome: order completes; the two-invoice deposit flow crossed services ──
            .then(order("SO-DEP-1").is_completed())
            .and(events_published(
                SalesOrderPlaced.EVENT_TYPE,
                DepositInvoiceRequested.EVENT_TYPE,
                SalesOrderUpfrontPaymentSettled.EVENT_TYPE,
                StockReserved.EVENT_TYPE,
                ShipmentPosted.EVENT_TYPE,
                SalesOrderShipped.EVENT_TYPE,
                CustomerInvoiceCreated.EVENT_TYPE,
                CustomerPaymentReceived.EVENT_TYPE));
    }
}
