package com.northwood.testharness.replenishment;

import static com.northwood.testharness.dsl.Dsl.a_bom;
import static com.northwood.testharness.dsl.Dsl.a_manufactured_product;
import static com.northwood.testharness.dsl.Dsl.a_raw_material;
import static com.northwood.testharness.dsl.Dsl.a_replenishment_request;
import static com.northwood.testharness.dsl.Dsl.a_routing;
import static com.northwood.testharness.dsl.Dsl.a_work_order_for;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Dsl.reorder_policy;
import static com.northwood.testharness.dsl.Dsl.reorder_point_breached;
import static com.northwood.testharness.dsl.Dsl.stock_on_hand;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;

import com.northwood.inventory.domain.ReplenishmentRequest;
import org.junit.jupiter.api.Test;

/**
 * Multi-level (sub-assembly) make-to-stock replenishment, the cross-service
 * subset of {@code StockReplenishmentSubAssemblyPathTest} (REQ-MFG-021).
 *
 * <p>A reorder-point breach on a finished good whose BOM contains a sub-assembly
 * releases BOTH a top-level (root) replenishment work order and a recursive child
 * work order for the sub-assembly, each a make-to-stock work order (no sales-order
 * peg).
 *
 * <p><b>Partial-fit port.</b> The imperative twin observes the structural state
 * immediately after release (both work orders at {@code work_order_created}, exact
 * parent-id wiring) without ticking the saga worker. The DSL settles to quiescence,
 * so this port seeds the sub-assembly raw stock the imperative omits and asserts the
 * durable cross-service outcome — both work orders exist and are make-to-stock —
 * leaving the precise parent-id / saga-state introspection to the imperative test.
 */
class StockReplenishmentSubAssemblyPathDslTest {

    @Test
    void reorder_breach_releases_a_root_and_a_sub_assembly_work_order() {
        scenario("a reorder breach on a product with a sub-assembly releases a root + child work order")

            // ── guard: a 2-level BOM — FG → (SUB-A sub-assembly + WOOD raw); SUB-A → STEEL raw ──
            .given(a_manufactured_product("FG-RPL-SUB", "Replenishment FG w/ Sub-Assembly"))
            .and(a_manufactured_product("SUB-A", "Sub-Assembly A"))
            .and(a_raw_material("WOOD", "Wood"))
            .and(a_raw_material("STEEL", "Steel"))
            .and(a_bom("FG-RPL-SUB").withSubAssembly("SUB-A", qty(1)).withRawLine("WOOD", qty(2)))
            .and(a_bom("SUB-A").withRawLine("STEEL", qty(3)))
            .and(a_routing("FG-RPL-SUB").singleOp())
            .and(a_routing("SUB-A").singleOp())
            .and(reorder_policy("FG-RPL-SUB").point(qty(5)).quantity(qty(10)))
            .and(stock_on_hand("FG-RPL-SUB", qty(3)).at(MAIN))   // below the reorder point
            .and(stock_on_hand("WOOD", qty(100)).at(MAIN))
            .and(stock_on_hand("STEEL", qty(100)).at(MAIN))

            // ── trigger: the reorder-point breach is detected ──
            .when(reorder_point_breached("FG-RPL-SUB"))
            // ── outcome: a manufacturing-routed replenishment + a root and a child WO, both make-to-stock ──
            .then(a_replenishment_request("FG-RPL-SUB")
                .routedTo(ReplenishmentRequest.TargetService.MANUFACTURING)
                .because(ReplenishmentRequest.Reason.REORDER_POINT_BREACH)
                .reaches(ReplenishmentRequest.Status.DISPATCHED))
            .and(a_work_order_for("FG-RPL-SUB").isMakeToStock())
            .and(a_work_order_for("SUB-A").isMakeToStock());
    }
}
