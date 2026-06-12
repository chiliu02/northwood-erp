package com.northwood.testharness.o2c;

import static com.northwood.testharness.dsl.Dsl.a_customer;
import static com.northwood.testharness.dsl.Dsl.a_product;
import static com.northwood.testharness.dsl.Dsl.customer;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.order;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.REJECTED;

import com.northwood.inventory.domain.events.ReplenishmentCancelled;
import com.northwood.manufacturing.domain.events.ReplenishmentUndispatchable;
import com.northwood.sales.domain.SalesOrder;
import org.junit.jupiter.api.Test;

/**
 * Unsourceable sales-order shortage → the order is rejected (REQ-MFG-080, REQ-INV-086, and the
 * {@code rejected} terminal of REQ-SAL-036 / REQ-XBC-030 / REQ-INV-080).
 *
 * <p>A sales order for a manufactured SKU with <b>no active BOM</b> is short on stock: inventory
 * raises a manufacturing-routed {@code sales_order_shortage} replenishment, but manufacturing
 * cannot release a work order (no BOM to explode) and emits
 * {@code manufacturing.ReplenishmentUndispatchable}; inventory cancels the request
 * ({@code inventory.ReplenishmentCancelled}); sales' fan-in drives the fulfilment saga + order
 * header to {@code rejected}. This is the terminal arm that the happy-path / shortage-then-replenish
 * tests never reach.
 *
 * <p>No imperative twin: this is a DSL-native scenario covering a gap the harness previously had no
 * test for (the dispatch → undispatchable → {@code ReplenishmentCancelled} → {@code rejected}
 * fan-in).
 */
class OrderToCashRejectedPathDslTest {

    @Test
    void an_unsourceable_shortage_rejects_the_order() {
        scenario("a sales order short on a manufactured SKU with no BOM is rejected")

            // ── guard: a sold, manufactured product with NO active BOM, no stock ──
            .given(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("ORPHAN-001", "No-BOM Widget").pricedAt(money(50)).manufacturedNoBom())

            // ── trigger: place an order for 3 — reservation fails, the shortage can't be sourced ──
            .when(customer("CUST-001").places_order("SO-REJ-1").line("ORPHAN-001", qty(3)))

            // ── outcome: manufacturing can't release (no BOM); inventory cancels; the order is rejected ──
            .then(order("SO-REJ-1").reaches(REJECTED))
            .and(order("SO-REJ-1").has_status(SalesOrder.Status.REJECTED))
            .and(events_published(
                ReplenishmentUndispatchable.EVENT_TYPE,
                ReplenishmentCancelled.EVENT_TYPE));
    }
}
