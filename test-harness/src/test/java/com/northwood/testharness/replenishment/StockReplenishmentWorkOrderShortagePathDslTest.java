package com.northwood.testharness.replenishment;

import static com.northwood.testharness.dsl.Dsl.a_bom;
import static com.northwood.testharness.dsl.Dsl.a_manufactured_product;
import static com.northwood.testharness.dsl.Dsl.a_purchasable_product;
import static com.northwood.testharness.dsl.Dsl.a_replenishment_request;
import static com.northwood.testharness.dsl.Dsl.a_routing;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.goods_received_for;
import static com.northwood.testharness.dsl.Dsl.money;
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
import com.northwood.manufacturing.domain.events.RawMaterialShortageDetected;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import org.junit.jupiter.api.Test;

/**
 * Trigger-B: a WO raw-material shortage routes a replenishment to purchasing
 * (REQ-INV-080 Trigger B, REQ-MFG-040, REQ-XBC-080 Trigger B, REQ-INV-087). The reorder-point
 * tests only exercise Trigger A (independent demand); this covers the dependent-demand BOM-explosion
 * trigger — and the manufacturing↔purchasing decoupling invariant, since the shortage flows through
 * inventory rather than manufacturing signalling purchasing directly.
 *
 * <p>A finished good drops below its reorder point → inventory releases a make-to-stock work order →
 * the WO release is short on its purchasable raw material → manufacturing emits
 * {@code RawMaterialShortageDetected} → inventory raises a {@code work_order_shortage}
 * {@code ReplenishmentRequest} routed to purchasing → PR→PO→goods receipt tops up the raw → the WO
 * completes → the FG replenishment is fulfilled.
 *
 * <p>No imperative twin: a DSL-native scenario covering the previously-untested Trigger-B path
 * (the sub-assembly replenishment test seeds raw stock, so it never triggers a raw shortage).
 */
class StockReplenishmentWorkOrderShortagePathDslTest {

    @Test
    void a_work_order_raw_shortage_routes_a_replenishment_to_purchasing() {
        scenario("a work order short on raw materials replenishes the raw via purchasing, then completes")

            // ── guard: a manufactured FG (1 purchasable raw per unit), reorder 5/10; FG below point; NO raw stock ──
            .given(a_manufactured_product("FG-WOS-001", "WO-Shortage FG"))
            .and(a_purchasable_product("RM-PUR-001", "Purchasable Raw").suppliedAt(money(8)))
            .and(a_bom("FG-WOS-001").withRawLine("RM-PUR-001", qty(1)))
            .and(a_routing("FG-WOS-001").singleOp())
            .and(reorder_policy("FG-WOS-001").point(qty(5)).quantity(qty(10)))
            .and(stock_on_hand("FG-WOS-001", qty(3)).at(MAIN))   // below the reorder point
            // RM-PUR-001: no stock seeded → the work order cannot reserve it

            // ── trigger: the FG reorder-point breach releases a make-to-stock work order ──
            .when(reorder_point_breached("FG-WOS-001"))
            // ── outcome: the WO is short on raw → a work_order_shortage replenishment routes to purchasing ──
            .then(a_replenishment_request("RM-PUR-001")
                .routedTo(ReplenishmentRequest.TargetService.PURCHASING)
                .because(ReplenishmentRequest.Reason.WORK_ORDER_SHORTAGE)
                .reaches(ReplenishmentRequest.Status.DISPATCHED))

            // ── trigger: the raw is received against its PO (for real) ──
            .when(goods_received_for("RM-PUR-001"))
            // ── trigger: with raw in stock, the work order completes (for real) ──
            .when(work_order_for("FG-WOS-001").completes_manufacturing())
            // ── outcome: raw + FG replenishments both fulfilled; the cross-service events crossed ──
            .then(a_replenishment_request("RM-PUR-001").reaches(ReplenishmentRequest.Status.FULFILLED))
            .and(a_replenishment_request("FG-WOS-001").reaches(ReplenishmentRequest.Status.FULFILLED))
            .and(events_published(
                ReplenishmentRequested.EVENT_TYPE,
                RawMaterialShortageDetected.EVENT_TYPE,
                WorkOrderManufacturingCompleted.EVENT_TYPE,
                ReplenishmentFulfilled.EVENT_TYPE));
    }
}
