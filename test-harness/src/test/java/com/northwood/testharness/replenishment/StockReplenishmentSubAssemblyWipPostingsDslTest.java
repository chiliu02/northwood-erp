package com.northwood.testharness.replenishment;

import static com.northwood.testharness.dsl.Dsl.a_bom;
import static com.northwood.testharness.dsl.Dsl.a_finance_card;
import static com.northwood.testharness.dsl.Dsl.a_journal;
import static com.northwood.testharness.dsl.Dsl.a_manufactured_product;
import static com.northwood.testharness.dsl.Dsl.a_raw_material;
import static com.northwood.testharness.dsl.Dsl.a_routing;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Dsl.reorder_policy;
import static com.northwood.testharness.dsl.Dsl.reorder_point_breached;
import static com.northwood.testharness.dsl.Dsl.stock_on_hand;
import static com.northwood.testharness.dsl.Dsl.work_order_for;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;

import com.northwood.finance.domain.JournalEntry;
import com.northwood.product.domain.ValuationClass;
import org.junit.jupiter.api.Test;

/**
 * The sub-assemblies-consumed WIP leg (REQ-FIN-028): when a parent work order completes, it consumes
 * the sub-assembly children it built — Dr parent WIP (1230) / Cr the sub-assembly's FG account (1220)
 * at standard cost. This is the leg the single-level WIP test could not assert, because it shares its
 * {@code WORK_ORDER_WIP} source-document type with the raw-materials-issued leg (REQ-FIN-026, Cr 1210);
 * {@code a_journal()} now disambiguates the two by their credit account.
 *
 * <p>Two-level BOM: FG → (1 × SUB-A + 2 × WOOD); SUB-A → (3 × STEEL). The child work order has no
 * replenishment request of its own (released by the parent's BOM walk), so it is completed by product
 * via {@code completes_as_sub_assembly()} before the parent completes (parent-on-children gating).
 */
class StockReplenishmentSubAssemblyWipPostingsDslTest {

    @Test
    void parent_completion_consuming_a_sub_assembly_posts_the_consumed_wip_leg() {
        scenario("a parent work order consuming its sub-assembly children posts Dr WIP / Cr FG")

            // ── guard: 2-level BOM, reorder 5/10 on the FG, raws stocked, standard costs seeded ──
            .given(a_manufactured_product("FG-SUBWIP", "WIP FG w/ Sub-Assembly"))
            .and(a_manufactured_product("SUBA-WIP", "WIP Sub-Assembly"))
            .and(a_raw_material("WOOD-W", "Wood"))
            .and(a_raw_material("STEEL-W", "Steel"))
            .and(a_bom("FG-SUBWIP").withSubAssembly("SUBA-WIP", qty(1)).withRawLine("WOOD-W", qty(2)))
            .and(a_bom("SUBA-WIP").withRawLine("STEEL-W", qty(3)))
            .and(a_routing("FG-SUBWIP").singleOp())
            .and(a_routing("SUBA-WIP").singleOp())
            .and(reorder_policy("FG-SUBWIP").point(qty(5)).quantity(qty(10)))
            .and(stock_on_hand("FG-SUBWIP", qty(3)).at(MAIN))   // below the reorder point
            .and(stock_on_hand("WOOD-W", qty(100)).at(MAIN))
            .and(stock_on_hand("STEEL-W", qty(100)).at(MAIN))
            // standard costs: SUB-A (semi-finished) at 3 → the consumed leg is 3 × 10 = 30
            .and(a_finance_card("STEEL-W", money(1), ValuationClass.RAW_MATERIALS))
            .and(a_finance_card("WOOD-W", money(1), ValuationClass.RAW_MATERIALS))
            .and(a_finance_card("SUBA-WIP", money(3), ValuationClass.SEMI_FINISHED_GOODS))
            .and(a_finance_card("FG-SUBWIP", money(5), ValuationClass.FINISHED_GOODS))

            // ── trigger: reorder-point breach releases the root + child work orders ──
            .when(reorder_point_breached("FG-SUBWIP"))
            // ── trigger: complete the child sub-assembly first, then the parent ──
            .when(work_order_for("SUBA-WIP").completes_as_sub_assembly())
            .when(work_order_for("FG-SUBWIP").completes_manufacturing())

            // ── outcome: REQ-FIN-028 — Dr 1230 WIP / Cr 1220 FG at SUB-A std cost × consumed qty (3 × 10) ──
            .then(a_journal().of_type(JournalEntry.SourceDocumentType.WORK_ORDER_WIP)
                .debiting("1230", money(30)).crediting("1220", money(30)).posted());
    }
}
