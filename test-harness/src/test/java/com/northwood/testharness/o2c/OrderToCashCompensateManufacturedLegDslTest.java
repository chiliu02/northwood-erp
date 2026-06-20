package com.northwood.testharness.o2c;

import static com.northwood.testharness.dsl.Dsl.a_bom;
import static com.northwood.testharness.dsl.Dsl.a_customer;
import static com.northwood.testharness.dsl.Dsl.a_product;
import static com.northwood.testharness.dsl.Dsl.a_raw_material;
import static com.northwood.testharness.dsl.Dsl.a_replenishment_request;
import static com.northwood.testharness.dsl.Dsl.a_routing;
import static com.northwood.testharness.dsl.Dsl.customer;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.order;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Dsl.stock_on_hand;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPENSATED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STOCK_RESERVATION_INCOMPLETE;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.events.InventorySalesOrderCancellationApplied;
import com.northwood.inventory.domain.events.OrderPeggedSupplyCancellationRequested;
import com.northwood.manufacturing.domain.events.WorkOrderCancellationApplied;
import com.northwood.manufacturing.domain.events.WorkOrderCancelled;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.sales.domain.events.SalesOrderCompensated;
import org.junit.jupiter.api.Test;

/**
 * Multi-leg compensation — the manufacturing leg. A {@code to_order} manufactured
 * SKU raises a dedicated order-pegged work order; cancelling the order <em>before
 * production starts</em> must withdraw that released work order (and hand its
 * reserved raw materials back to the pool), not orphan it. The mirror of the
 * purchasing-leg test: the sales saga fans the undo out to manufacturing (via
 * inventory, which owns the peg→WO map), parks in {@code compensating}, and reaches
 * {@code compensated} once manufacturing acks the work-order withdrawal.
 *
 * <p>The full chain runs under the real services + saga managers + inbox handlers:
 * {@code SalesOrderCancellationRequested → InventorySalesOrderCancellationApplied}
 * (enumerating the manufacturing leg) {@code → OrderPeggedSupplyCancellationRequested →}
 * {@code WorkOrder.cancel} (emits {@code WorkOrderCancelled}, inventory releases the
 * raw materials) {@code → WorkOrderCancellationApplied →} the saga drains its single
 * leg and emits {@code SalesOrderCompensated}.
 */
class OrderToCashCompensateManufacturedLegDslTest {

    @Test
    void cancelling_a_to_order_line_withdraws_the_released_work_order_and_compensates() {
        scenario("cancel a make-to-order line before production: the released work order is withdrawn, the order compensates")

            // ── guard: a sold, make-to-order product with a single-op BOM; raw stock on hand ──
            .given(a_customer("CUST-001", "Acme Corp"))
            .and(a_product("FG-MTO-001", "Made-to-order FG").pricedAt(money(250)).manufacturedToOrder())
            .and(a_raw_material("RM-001", "Raw Material 1"))
            .and(a_bom("FG-MTO-001").withRawLine("RM-001", qty(1)))
            .and(a_routing("FG-MTO-001").singleOp())
            .and(stock_on_hand("RM-001", qty(100)).at(MAIN))

            // ── trigger: place an order — the line parks awaiting a dedicated pegged WO ──
            .when(customer("CUST-001").places_order("SO-CMP-MFG-1").line("FG-MTO-001", qty(3)))
            .then(order("SO-CMP-MFG-1").reaches(STOCK_RESERVATION_INCOMPLETE))
            .and(a_replenishment_request("FG-MTO-001")
                .routedTo(ReplenishmentRequest.TargetService.MANUFACTURING)
                .because(ReplenishmentRequest.Reason.ORDER_PEGGED)
                .ofQuantity(qty(3))
                .forOrder("SO-CMP-MFG-1")
                .reaches(ReplenishmentRequest.Status.DISPATCHED))

            // ── trigger: cancel before production — the work order is still released ──
            .when(customer("CUST-001").cancels("SO-CMP-MFG-1").because("customer changed mind"))

            // ── outcome: the manufacturing leg withdraws the work order, the saga compensates ──
            .then(order("SO-CMP-MFG-1").reaches(COMPENSATED))
            .and(order("SO-CMP-MFG-1").has_status(SalesOrder.Status.CANCELLED))
            .and(events_published(
                SalesOrderCancellationRequested.EVENT_TYPE,
                InventorySalesOrderCancellationApplied.EVENT_TYPE,
                OrderPeggedSupplyCancellationRequested.EVENT_TYPE,
                WorkOrderCancellationApplied.EVENT_TYPE,
                WorkOrderCancelled.EVENT_TYPE,
                SalesOrderCompensated.EVENT_TYPE));
    }
}
