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
 * Perpetual-WIP make-cycle with conversion cost + efficiency variance
 * (REQ-FIN-029), end-to-end across inventory → manufacturing → finance.
 *
 * <p>Unlike {@link StockReplenishmentWipPostingsDslTest} (material legs only, no
 * work-centre rates), this seeds a conversion rate so the conversion + variance
 * legs fire, and asserts all four WIP legs net to zero with <b>derived</b> amounts:
 * <ul>
 *   <li>routing: 1 op, 15 + 60 = 75 planned min/unit; actual completion logs 45 min/unit;</li>
 *   <li>rate: 0.40 labour + 0.20 overhead = 0.60/min;</li>
 *   <li>standard conversion = 75 × 0.60 = 45/unit; actual = 45 × 0.60 = 27/unit;</li>
 *   <li>WO qty 10 → material 10×8=80, actual conversion 270, standard conversion 450,
 *       efficiency variance 270 − 450 = −180 (favourable), FG std cost (8 + 45) × 10 = 530.</li>
 * </ul>
 * WIP debits 80 + 270 + 180 = 530; credits 530 → nets to zero, variance 180 in 5100.
 */
class StockReplenishmentConversionWipDslTest {

    @Test
    void the_make_cycle_absorbs_conversion_and_clears_the_efficiency_variance() {
        scenario("a make-to-stock work order absorbs conversion cost and clears the efficiency variance")

            .given(a_manufactured_product("FG-CONV-001", "Conversion FG"))
            .and(a_raw_material("RM-CONV-001", "Conversion Raw"))
            .and(a_bom("FG-CONV-001").withRawLine("RM-CONV-001", qty(1)))
            // single-op routing + a work-centre conversion rate (0.40 labour + 0.20 overhead /min).
            .and(a_routing("FG-CONV-001").singleOp().withConversionRatePerMinute("0.40", "0.20"))
            .and(reorder_policy("FG-CONV-001").point(qty(5)).quantity(qty(10)))
            .and(stock_on_hand("FG-CONV-001", qty(3)).at(MAIN))
            .and(stock_on_hand("RM-CONV-001", qty(100)).at(MAIN))
            // FG standard cost = material (1×8) + standard conversion (75min × 0.60) = 53.
            .and(a_finance_card("RM-CONV-001", money(8), ValuationClass.RAW_MATERIALS))
            .and(a_finance_card("FG-CONV-001", money(53), ValuationClass.FINISHED_GOODS))

            .when(reorder_point_breached("FG-CONV-001"))
            .when(work_order_for("FG-CONV-001").completes_manufacturing())

            .then(a_replenishment_request("FG-CONV-001").reaches(ReplenishmentRequest.Status.FULFILLED))
            // REQ-FIN-026 — raw materials issued to WIP: Dr 1230 / Cr 1210 (10 × 8).
            .and(a_journal().of_type(JournalEntry.SourceDocumentType.WORK_ORDER_WIP)
                .debiting("1230", money(80)).crediting("1210", money(80)).posted())
            // REQ-FIN-029 — conversion applied at actual: Dr 1230 WIP / Cr 5250 (10 × 27).
            .and(a_journal().of_type(JournalEntry.SourceDocumentType.WORK_ORDER_WIP)
                .debiting("1230", money(270)).crediting("5250", money(270)).posted())
            // REQ-FIN-029 — favourable efficiency variance (actual 270 − standard 450): Dr 1230 WIP / Cr 5100 (180).
            .and(a_journal().of_type(JournalEntry.SourceDocumentType.WORK_ORDER_WIP)
                .debiting("1230", money(180)).crediting("5100", money(180)).posted())
            // REQ-FIN-027 — WO completion at full standard cost: Dr 1220 FG / Cr 1230 WIP (10 × 53).
            .and(a_journal().of_type(JournalEntry.SourceDocumentType.WORK_ORDER_COMPLETION)
                .debiting("1220", money(530)).crediting("1230", money(530)).posted())
            // WIP nets to zero per work order, even with conversion + variance.
            .and(gl_account("1230").netsToZero());
    }
}
