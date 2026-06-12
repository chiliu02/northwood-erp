package com.northwood.testharness.replenishment;

import static com.northwood.testharness.dsl.Dsl.a_bom;
import static com.northwood.testharness.dsl.Dsl.a_finance_card;
import static com.northwood.testharness.dsl.Dsl.a_journal;
import static com.northwood.testharness.dsl.Dsl.a_manufactured_product;
import static com.northwood.testharness.dsl.Dsl.a_raw_material;
import static com.northwood.testharness.dsl.Dsl.a_replenishment_request;
import static com.northwood.testharness.dsl.Dsl.a_routing;
import static com.northwood.testharness.dsl.Dsl.gl_account;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Dsl.reorder_policy;
import static com.northwood.testharness.dsl.Dsl.reorder_point_breached;
import static com.northwood.testharness.dsl.Dsl.stock_on_hand;
import static com.northwood.testharness.dsl.Dsl.work_order_for;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;

import com.northwood.finance.domain.JournalEntry;
import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.product.domain.ValuationClass;
import org.junit.jupiter.api.Test;

/**
 * Perpetual-WIP make-cycle GL postings (REQ-FIN-026, REQ-FIN-027): a make-to-stock work order posts
 * Dr WIP / Cr Raw Materials when raw materials are issued, and Dr Finished Goods / Cr WIP on
 * completion — both at standard cost, so WIP nets to zero per work order.
 *
 * <p>Extends the manufactured-replenishment path with finance standard-cost + valuation-class cards
 * (without which the WIP journals skip on a zero cost) and asserts the two WIP journals plus WIP
 * account conservation. No imperative twin: the harness's manufacturing / replenishment tests assert
 * no GL postings, so this is the GL-detail coverage for the make cycle.
 */
class StockReplenishmentWipPostingsDslTest {

    @Test
    void the_make_cycle_posts_wip_journals_at_standard_cost() {
        scenario("a make-to-stock work order posts the perpetual-WIP make-cycle journals")

            // ── guard: FG (1 raw/unit) reorder 5/10, below point; raw in stock; standard costs seeded ──
            .given(a_manufactured_product("FG-WIP-001", "WIP FG"))
            .and(a_raw_material("RM-WIP-001", "WIP Raw"))
            .and(a_bom("FG-WIP-001").withRawLine("RM-WIP-001", qty(1)))
            .and(a_routing("FG-WIP-001").singleOp())
            .and(reorder_policy("FG-WIP-001").point(qty(5)).quantity(qty(10)))
            .and(stock_on_hand("FG-WIP-001", qty(3)).at(MAIN))
            .and(stock_on_hand("RM-WIP-001", qty(100)).at(MAIN))
            // standard costs: FG cost == its rolled-up material cost (1 × 8), so WIP nets to zero
            .and(a_finance_card("RM-WIP-001", money(8), ValuationClass.RAW_MATERIALS))
            .and(a_finance_card("FG-WIP-001", money(8), ValuationClass.FINISHED_GOODS))

            // ── trigger: reorder-point breach releases a make-to-stock work order for 10 ──
            .when(reorder_point_breached("FG-WIP-001"))
            // ── trigger: the work order is built and completed (for real) ──
            .when(work_order_for("FG-WIP-001").completes_manufacturing())

            // ── outcome: the replenishment fulfils and the two WIP make-cycle journals posted ──
            .then(a_replenishment_request("FG-WIP-001").reaches(ReplenishmentRequest.Status.FULFILLED))
            // REQ-FIN-026 — raw materials issued to WIP: Dr 1230 WIP / Cr 1210 Raw Materials (10 × 8)
            .and(a_journal().of_type(JournalEntry.SourceDocumentType.WORK_ORDER_WIP)
                .debiting("1230", money(80)).crediting("1210", money(80)).posted())
            // REQ-FIN-027 — WO completion, FG out of WIP: Dr 1220 FG / Cr 1230 WIP (10 × 8)
            .and(a_journal().of_type(JournalEntry.SourceDocumentType.WORK_ORDER_COMPLETION)
                .debiting("1220", money(80)).crediting("1230", money(80)).posted())
            // WIP nets to zero per work order (every leg at standard cost)
            .and(gl_account("1230").netsToZero());
    }
}
