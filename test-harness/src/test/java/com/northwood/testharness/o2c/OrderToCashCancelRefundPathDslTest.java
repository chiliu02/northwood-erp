package com.northwood.testharness.o2c;

import static com.northwood.testharness.dsl.Dsl.a_customer;
import static com.northwood.testharness.dsl.Dsl.a_deposit_invoice;
import static com.northwood.testharness.dsl.Dsl.a_journal;
import static com.northwood.testharness.dsl.Dsl.a_product;
import static com.northwood.testharness.dsl.Dsl.customer;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.gl_account;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.order;
import static com.northwood.testharness.dsl.Dsl.percent;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Dsl.stock_on_hand;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPENSATED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.AWAITING_PREPAYMENT;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.SUPPLY_SECURED;

import com.northwood.finance.domain.JournalEntry;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import org.junit.jupiter.api.Test;

/**
 * Refund on a cancelled deposit order, ported from {@code CancelRefundPathTest}
 * (REQ-SAL-036 pre-shipment cancellation; REQ-FIN-012/032 prepayment reversal).
 *
 * <p>A 50%-deposit order is placed and the deposit paid (Cr Customer Deposits
 * 2110), then cancelled before shipment. Finance's refund handler reverses the
 * deposit (Dr 2110 / Cr Bank 1000) so 2110 nets to zero across the deposit + the
 * refund, while the sales↔inventory compensation completes the saga to
 * {@code compensated} and the order folds to {@code CANCELLED}.
 *
 * <p>Introduces the GL-posting assertion vocabulary — {@code a_journal()
 * .of_type(...).debiting(...).crediting(...).posted()} and
 * {@code gl_account(code).netsToZero()} — the first DslTest to check journal
 * detail. The cancellation here fires from {@code SUPPLY_SECURED} (the deposit
 * payment settles the order all the way forward), exercising REQ-SAL-036's
 * "cancellation at any pre-shipment state" more fully than the imperative
 * original, which cancelled from {@code deposit_paid}.
 */
class OrderToCashCancelRefundPathDslTest {

    @Test
    void cancelling_a_paid_deposit_order_refunds_the_deposit() {
        scenario("cancel a paid-deposit order before shipment: deposit refunded, 2110 nets to zero")

            // ── guard: a priced product, in stock, for a known customer ──
            .given(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("FG-001", "Finished Good 1").pricedAt(money(100)))
            .and(stock_on_hand("FG-001", qty(50)).at(MAIN))

            // ── trigger: place a 50%-deposit order; deposit invoice raised ──
            .when(customer("CUST-001").places_order("SO-RFD-1").with_deposit(percent(50))
                .line("FG-001", qty(3)))
            .then(order("SO-RFD-1").reaches(AWAITING_PREPAYMENT))
            .and(a_deposit_invoice().for_order("SO-RFD-1").totalling(money(150)))

            // ── trigger: pay the deposit (Cr 2110); order settles to ready-to-ship ──
            .when(customer("CUST-001").pays(money(150)).against_deposit_on("SO-RFD-1"))
            .then(order("SO-RFD-1").reaches(SUPPLY_SECURED))

            // ── trigger: cancel before shipment ──
            .when(customer("CUST-001").cancels("SO-RFD-1").because("customer changed mind"))
            // ── outcome: compensated + cancelled; the deposit is refunded and 2110 nets to zero ──
            .then(order("SO-RFD-1").reaches(COMPENSATED))
            .and(order("SO-RFD-1").has_status(SalesOrder.Status.CANCELLED))
            .and(a_journal().of_type(JournalEntry.SourceDocumentType.CUSTOMER_REFUND)
                .debiting("2110", money(150)).crediting("1000", money(150)).posted())
            .and(gl_account("2110").netsToZero())
            .and(events_published(SalesOrderCancellationRequested.EVENT_TYPE));
    }
}
