package com.northwood.testharness.replenishment;

import static com.northwood.testharness.dsl.Dsl.a_purchasable_product;
import static com.northwood.testharness.dsl.Dsl.a_replenishment_request;
import static com.northwood.testharness.dsl.Dsl.events_published;
import static com.northwood.testharness.dsl.Dsl.goods_received_for;
import static com.northwood.testharness.dsl.Dsl.money;
import static com.northwood.testharness.dsl.Dsl.qty;
import static com.northwood.testharness.dsl.Dsl.reorder_policy;
import static com.northwood.testharness.dsl.Dsl.reorder_point_breached;
import static com.northwood.testharness.dsl.Dsl.stock_on_hand;
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.purchasing.domain.events.PurchaseRequisitionCreated;
import org.junit.jupiter.api.Test;

/**
 * Purchased-path stock replenishment, ported from
 * {@code StockReplenishmentPurchasedPathTest} (REQ-XBC-080 Trigger A buy,
 * REQ-PUR-020, REQ-INV-080/084). A purchasable raw material drops below its
 * reorder point; inventory raises a purchasing-routed replenishment that
 * purchasing turns into a stock-replenishment PR auto-converted to a PO, and a
 * goods receipt against that PO fulfils the request.
 *
 * <p><b>More faithful than its imperative twin.</b> The hand-written test fulfils
 * the request via a direct {@code markFulfilled} shortcut; this port drives the
 * <em>real</em> {@code GoodsReceiptService.post} against the auto-created PO,
 * which bumps on-hand and fulfils the linked replenishment as production does.
 */
class StockReplenishmentPurchasedPathDslTest {

    @Test
    void reorder_point_breach_creates_a_pr_then_po_then_fulfils_on_receipt() {
        scenario("a raw material below its reorder point is replenished by a purchase order")

            // ── guard: a purchasable raw material with a supplier price, reorder 5/10 ──
            .given(a_purchasable_product("RM-PUR-001", "Purchased Raw Material").suppliedAt(money(12)))
            .and(reorder_policy("RM-PUR-001").point(qty(5)).quantity(qty(10)))
            .and(stock_on_hand("RM-PUR-001", qty(3)).at(MAIN))   // below the reorder point

            // ── trigger: the reorder-point breach is detected ──
            .when(reorder_point_breached("RM-PUR-001"))
            // ── outcome: a purchasing-routed replenishment dispatched as a PR (auto-converted to a PO) ──
            .then(a_replenishment_request("RM-PUR-001")
                .routedTo(ReplenishmentRequest.TargetService.PURCHASING)
                .because(ReplenishmentRequest.Reason.REORDER_POINT_BREACH)
                .ofQuantity(qty(10))
                .reaches(ReplenishmentRequest.Status.DISPATCHED))

            // ── trigger: goods are received against the PO (for real) ──
            .when(goods_received_for("RM-PUR-001"))
            // ── outcome: the replenishment is fulfilled; the cross-service chain crossed ──
            .then(a_replenishment_request("RM-PUR-001").reaches(ReplenishmentRequest.Status.FULFILLED))
            .and(events_published(
                ReplenishmentRequested.EVENT_TYPE,
                PurchaseRequisitionCreated.EVENT_TYPE,
                PurchaseOrderCreated.EVENT_TYPE,
                GoodsReceived.EVENT_TYPE,
                ReplenishmentFulfilled.EVENT_TYPE));
    }
}
