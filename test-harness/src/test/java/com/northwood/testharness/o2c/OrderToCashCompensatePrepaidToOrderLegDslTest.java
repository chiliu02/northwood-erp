package com.northwood.testharness.o2c;

import static com.northwood.testharness.dsl.Dsl.a_customer;
import static com.northwood.testharness.dsl.Dsl.a_deposit_invoice;
import static com.northwood.testharness.dsl.Dsl.a_journal;
import static com.northwood.testharness.dsl.Dsl.a_product;
import static com.northwood.testharness.dsl.Dsl.a_replenishment_request;
import static com.northwood.testharness.dsl.Dsl.customer;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.gl_account;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.order;
import static com.northwood.testharness.dsl.Dsl.percent;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.AWAITING_PREPAYMENT;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPENSATED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STOCK_RESERVATION_INCOMPLETE;

import com.northwood.finance.domain.JournalEntry;
import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.events.OrderPeggedSupplyCancellationRequested;
import com.northwood.purchasing.domain.events.PurchaseOrderCancellationApplied;
import com.northwood.purchasing.domain.events.PurchaseOrderCancelled;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.events.SalesOrderCompensated;
import org.junit.jupiter.api.Test;

/**
 * Multi-leg compensation meets the deposit refund — the finance ↔ compensation seam.
 * A <b>prepaid</b> {@code to_order} purchased line: the customer pays a deposit (parked
 * in 2110 Customer Deposits), the line raises a dedicated order-pegged PO, then the
 * order is cancelled before goods receipt. Two undos must both happen, and in the
 * right order:
 *
 * <ul>
 *   <li>the committed PO is <b>withdrawn</b> (the purchasing compensation leg), so the
 *       saga drains its single leg and reaches {@code compensated};</li>
 *   <li>only <b>then</b> — on the confirmed {@code sales.SalesOrderCompensated} terminal,
 *       not the cancel request — finance refunds the deposit (Dr 2110 / Cr 1000), so
 *       2110 nets to zero.</li>
 * </ul>
 *
 * This is the scenario the refund-trigger fix is built for: the refund waits for the
 * confirmed terminal, which here only arrives after the PO leg is drained.
 */
class OrderToCashCompensatePrepaidToOrderLegDslTest {

    @Test
    void cancelling_a_prepaid_to_order_line_withdraws_the_po_and_refunds_the_deposit() {
        scenario("cancel a paid-deposit buy-to-order line before receipt: PO withdrawn, then deposit refunded")

            // ── guard: a sold, buy-to-order product (price 1200, supplier 700); NO stock ──
            .given(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("FG-CARPET-001", "Custom-design Carpet")
                .pricedAt(money(1200)).purchasedToOrder(money(700)))

            // ── trigger: place a 50%-deposit order; deposit invoice raised, up-front gate ──
            .when(customer("CUST-001").places_order("SO-CMP-DEP-1").with_deposit(percent(50))
                .line("FG-CARPET-001", qty(1)))
            .then(order("SO-CMP-DEP-1").reaches(AWAITING_PREPAYMENT))
            .and(a_deposit_invoice().for_order("SO-CMP-DEP-1").totalling(money(600)))

            // ── trigger: pay the deposit (Cr 2110); the line then parks awaiting its pegged PO ──
            .when(customer("CUST-001").pays(money(600)).against_deposit_on("SO-CMP-DEP-1"))
            .then(order("SO-CMP-DEP-1").reaches(STOCK_RESERVATION_INCOMPLETE))
            .and(a_replenishment_request("FG-CARPET-001")
                .routedTo(ReplenishmentRequest.TargetService.PURCHASING)
                .because(ReplenishmentRequest.Reason.ORDER_PEGGED)
                .ofQuantity(qty(1))
                .forOrder("SO-CMP-DEP-1")
                .reaches(ReplenishmentRequest.Status.DISPATCHED))

            // ── trigger: cancel before goods receipt ──
            .when(customer("CUST-001").cancels("SO-CMP-DEP-1").because("customer changed mind"))

            // ── outcome: the PO is withdrawn → saga compensates → THEN the deposit refunds ──
            .then(order("SO-CMP-DEP-1").reaches(COMPENSATED))
            .and(order("SO-CMP-DEP-1").has_status(SalesOrder.Status.CANCELLED))
            .and(a_journal().of_type(JournalEntry.SourceDocumentType.CUSTOMER_REFUND)
                .debiting("2110", money(600)).crediting("1000", money(600)).posted())
            .and(gl_account("2110").netsToZero())
            .and(events_published(
                OrderPeggedSupplyCancellationRequested.EVENT_TYPE,
                PurchaseOrderCancellationApplied.EVENT_TYPE,
                PurchaseOrderCancelled.EVENT_TYPE,
                SalesOrderCompensated.EVENT_TYPE));
    }
}
