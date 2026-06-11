package com.northwood.testharness.o2c;

import static com.northwood.testharness.dsl.Dsl.a_customer;
import static com.northwood.testharness.dsl.Dsl.a_product;
import static com.northwood.testharness.dsl.Dsl.customer;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.order;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Dsl.stock_on_hand;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPENSATED;

import com.northwood.inventory.domain.events.InventorySalesOrderCancellationApplied;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.sales.domain.events.SalesOrderCompensated;
import org.junit.jupiter.api.Test;

/**
 * Slice-3 of the acceptance-test DSL (see {@code docs/test-harness-dsl.md} §9) — the
 * cancellation / compensation branch, ported from {@code o2c/CancelCompensationTest}.
 *
 * <p>This is the branch the doc predicted would force the {@code .without_settling()}
 * escape hatch (§6): the hand-written test cancels a <em>freshly-placed</em> order
 * before the worker reserves stock. The DSL otherwise settles after every action,
 * which would advance the saga to {@code SUPPLY_SECURED} (stock reserved) and so
 * change the branch. {@code places_order(…).without_settling()} keeps the saga at
 * its {@code started} state with {@code SalesOrderPlaced} still pending;
 * {@code cancels(…)} then settles, draining the parked placement alongside the
 * cancellation and driving the compensation Saga to {@code compensated}.
 *
 * <p>The real services, saga manager + worker, inbox handlers, and Jackson 3 serde
 * run under every step — only the timing (the placement's deferred settle) and
 * naming are abstracted; the compensation behaviour is unforged.
 */
class OrderToCashCancellationPathDslTest {

    @Test
    void cancelling_a_freshly_placed_order_compensates_and_releases() {
        scenario("cancel before fulfilment: order compensates and stock is released")

            // ── guard: a priced product, in stock, for a known customer ──
            .given(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("WIDGET-001", "Widget").pricedAt(money(100)))
            .and(stock_on_hand("WIDGET-001", qty(50)).at(MAIN))

            // ── trigger: customer places an order — but the worker has not run yet ──
            .when(customer("CUST-001").places_order("SO-CXL-001")
                .line("WIDGET-001", qty(3)).without_settling())

            // ── trigger: customer cancels before the order is fulfilled ──
            .when(customer("CUST-001").cancels("SO-CXL-001").because("customer changed mind"))

            // ── outcome: compensation completes; the order is cancelled ──
            .then(order("SO-CXL-001").reaches(COMPENSATED))
            .and(order("SO-CXL-001").has_status(SalesOrder.Status.CANCELLED))
            .and(events_published(
                SalesOrderCancellationRequested.EVENT_TYPE,
                InventorySalesOrderCancellationApplied.EVENT_TYPE,
                SalesOrderCompensated.EVENT_TYPE));
    }
}
