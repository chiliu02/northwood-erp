package com.northwood.testharness.o2c;

import static com.northwood.testharness.dsl.Dsl.a_customer;
import static com.northwood.testharness.dsl.Dsl.a_product;
import static com.northwood.testharness.dsl.Dsl.clock_at;
import static com.northwood.testharness.dsl.Dsl.customer;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.order;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Dsl.stock_on_hand;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.AWAITING_RELEASE;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPENSATED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.READY_TO_SHIP;

import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.sales.domain.events.SalesOrderCompensated;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Planning-time-fence business outcomes (REQ-SAL-013/037), the cross-service
 * subset of {@code OrderToCashPlanningFenceTest}. A product carrying a planning
 * time fence defers the reservation leg of a far-future order until
 * {@code need-by − fence}, read against the world clock — so the order parks at
 * {@code awaiting_release} rather than reserving on placement.
 *
 * <p>The DSL ports the <em>outcomes</em> — parks vs reserves-immediately, and
 * cancel-from-parked compensates. The imperative test's decide-once /
 * {@code parkUntil} / {@code reservationsReleased}-payload mechanics stay below
 * DSL altitude and remain its job; no real time passes here either (the clock is
 * set, never advanced).
 */
class OrderToCashPlanningFenceDslTest {

    @Test
    void far_future_fenced_order_parks_at_awaiting_release() {
        scenario("a fenced product ordered far in the future parks until its release date")
            .given(clock_at(LocalDate.of(2026, 6, 1)))
            .and(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("WIDGET-001", "Widget").pricedAt(money(100)).withPlanningFence(7))
            // Stock is available, so the only thing that can hold the order is the fence.
            .and(stock_on_hand("WIDGET-001", qty(50)).at(MAIN))

            // need-by 2026-07-01; release = need-by − 7d = 2026-06-24, after the clock → parks.
            .when(customer("CUST-001").places_order("SO-FENCE-1").needBy(LocalDate.of(2026, 7, 1))
                .line("WIDGET-001", qty(3)))
            .then(order("SO-FENCE-1").reaches(AWAITING_RELEASE));
    }

    @Test
    void order_inside_the_fence_reserves_immediately() {
        scenario("a fenced product ordered within the fence reserves on placement")
            .given(clock_at(LocalDate.of(2026, 6, 1)))
            .and(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("WIDGET-001", "Widget").pricedAt(money(100)).withPlanningFence(7))
            .and(stock_on_hand("WIDGET-001", qty(50)).at(MAIN))

            // need-by 2026-06-05; release = 2026-05-29, already before the clock → reserves now.
            .when(customer("CUST-001").places_order("SO-FENCE-2").needBy(LocalDate.of(2026, 6, 5))
                .line("WIDGET-001", qty(3)))
            .then(order("SO-FENCE-2").reaches(READY_TO_SHIP));
    }

    @Test
    void cancelling_a_parked_order_compensates() {
        scenario("cancelling a fence-parked order still compensates")
            .given(clock_at(LocalDate.of(2026, 6, 1)))
            .and(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("WIDGET-001", "Widget").pricedAt(money(100)).withPlanningFence(7))
            .and(stock_on_hand("WIDGET-001", qty(50)).at(MAIN))

            .when(customer("CUST-001").places_order("SO-FENCE-3").needBy(LocalDate.of(2026, 7, 1))
                .line("WIDGET-001", qty(3)))
            .then(order("SO-FENCE-3").reaches(AWAITING_RELEASE))

            .when(customer("CUST-001").cancels("SO-FENCE-3").because("changed mind before release"))
            .then(order("SO-FENCE-3").reaches(COMPENSATED))
            .and(order("SO-FENCE-3").has_status(SalesOrder.Status.CANCELLED))
            .and(events_published(
                SalesOrderCancellationRequested.EVENT_TYPE,
                SalesOrderCompensated.EVENT_TYPE));
    }
}
