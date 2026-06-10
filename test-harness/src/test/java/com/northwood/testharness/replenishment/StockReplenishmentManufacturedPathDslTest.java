package com.northwood.testharness.replenishment;

import static com.northwood.testharness.dsl.Dsl.a_bom;
import static com.northwood.testharness.dsl.Dsl.a_manufactured_product;
import static com.northwood.testharness.dsl.Dsl.a_raw_material;
import static com.northwood.testharness.dsl.Dsl.a_replenishment_request;
import static com.northwood.testharness.dsl.Dsl.a_routing;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Dsl.reorder_policy;
import static com.northwood.testharness.dsl.Dsl.reorder_point_breached;
import static com.northwood.testharness.dsl.Dsl.stock_on_hand;
import static com.northwood.testharness.dsl.Dsl.work_order_for;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.manufacturing.domain.events.ReplenishmentDispatched;
import com.northwood.manufacturing.domain.events.WorkOrderCreated;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import org.junit.jupiter.api.Test;

/**
 * Manufactured-path stock replenishment, ported from
 * {@code StockReplenishmentManufacturedPathTest} (REQ-XBC-080 Trigger A make,
 * REQ-MFG-030, REQ-INV-080/084). A finished good drops below its reorder point,
 * inventory raises a manufacturing-routed replenishment, manufacturing releases
 * a make-to-stock work order, and on the work order's completion inventory bumps
 * the finished-good stock and marks the replenishment fulfilled.
 *
 * <p><b>More faithful than its imperative twin.</b> The hand-written test forges
 * the {@code WorkOrderManufacturingCompleted} event (it seeds no raw stock, so
 * the work order could never reserve materials and complete for real). This port
 * seeds raw-material stock and drives the <em>real</em> work order to completion
 * through {@code WorkOrderOperationService} — the completion event is produced by
 * production code, not forged.
 */
class StockReplenishmentManufacturedPathDslTest {

    @Test
    void reorder_point_breach_releases_a_work_order_and_fulfils_on_completion() {
        scenario("a finished good below its reorder point is replenished by a make-to-stock work order")

            // ── guard: a manufactured FG (1 raw per unit, single-op routing), reorder 5/10 ──
            .given(a_manufactured_product("FG-RPL-001", "Replenishment FG"))
            .and(a_raw_material("RM-001", "Raw Material 1"))
            .and(a_bom("FG-RPL-001").withRawLine("RM-001", qty(1)))
            .and(a_routing("FG-RPL-001").singleOp())
            .and(reorder_policy("FG-RPL-001").point(qty(5)).quantity(qty(10)))
            .and(stock_on_hand("FG-RPL-001", qty(3)).at(MAIN))   // below the reorder point
            .and(stock_on_hand("RM-001", qty(100)).at(MAIN))     // enough to build the work order

            // ── trigger: the reorder-point breach is detected ──
            .when(reorder_point_breached("FG-RPL-001"))
            // ── outcome: a make-to-stock work order is released for 10 units ──
            .then(a_replenishment_request("FG-RPL-001")
                .routedTo(ReplenishmentRequest.TargetService.MANUFACTURING)
                .because(ReplenishmentRequest.Reason.REORDER_POINT_BREACH)
                .ofQuantity(qty(10))
                .reaches(ReplenishmentRequest.Status.DISPATCHED))

            // ── trigger: the work order is built and completed (for real) ──
            .when(work_order_for("FG-RPL-001").completes_manufacturing())
            // ── outcome: the replenishment is fulfilled; the cross-service events crossed ──
            .then(a_replenishment_request("FG-RPL-001").reaches(ReplenishmentRequest.Status.FULFILLED))
            .and(events_published(
                ReplenishmentRequested.EVENT_TYPE,
                ReplenishmentDispatched.EVENT_TYPE,
                WorkOrderCreated.EVENT_TYPE,
                WorkOrderManufacturingCompleted.EVENT_TYPE,
                ReplenishmentFulfilled.EVENT_TYPE));
    }
}
