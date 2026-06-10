package com.northwood.testharness.p2p;

import static com.northwood.testharness.dsl.Dsl.a_to_order_product;
import static com.northwood.testharness.dsl.Dsl.a_to_stock_product;
import static com.northwood.testharness.dsl.Dsl.buyer;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Dsl.the_requisition;
import static com.northwood.testharness.dsl.Dsl.the_system;
import static com.northwood.testharness.dsl.Scenario.scenario;

import org.junit.jupiter.api.Test;

/**
 * To-order manual-purchase guard, ported from {@code PurchaseRequisitionToOrderGuardTest}
 * (REQ-PUR-020, REQ-PROD-022). A to-order product must not be bought on a manual
 * requisition (its received stock would orphan in free ATP), but the system
 * order-pegged buy path must stay open for exactly such products.
 *
 * <p>Partial-fit: this is a synchronous application-service guard with no bus /
 * settle interaction, so it ports as degenerate one-{@code when} scenarios whose
 * {@code when} captures the accept/reject outcome rather than driving a saga.
 */
class PurchaseRequisitionToOrderGuardDslTest {

    @Test
    void manual_requisition_for_a_to_order_product_is_rejected() {
        scenario("a manual requisition for a to-order product is rejected")
            .given(a_to_order_product("FG-CARPET-001", "Custom-design Carpet"))
            .when(buyer().attempts_requisition("PR-TO-ORDER").line("FG-CARPET-001", qty(1)))
            .then(the_requisition().was_rejected_as_to_order());
    }

    @Test
    void manual_requisition_for_a_to_stock_product_is_accepted() {
        scenario("a manual requisition for a to-stock product is accepted")
            .given(a_to_stock_product("RM-101", "Raw Material 101"))
            .when(buyer().attempts_requisition("PR-TO-STOCK").line("RM-101", qty(10)))
            .then(the_requisition().was_accepted());
    }

    @Test
    void system_order_pegged_buy_for_a_to_order_product_is_not_blocked() {
        scenario("the system order-pegged buy path is not blocked for a to-order product")
            .given(a_to_order_product("FG-CARPET-001", "Custom-design Carpet"))
            .when(the_system().places_a_pegged_buy("PR-PEGGED").line("FG-CARPET-001", qty(1)))
            .then(the_requisition().was_created());
    }
}
