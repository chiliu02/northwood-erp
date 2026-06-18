package com.northwood.testharness.load;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.finance.domain.JournalEntry;
import com.northwood.finance.domain.JournalEntryLine;
import com.northwood.inventory.application.dto.StockBalanceView;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.testharness.dsl.World;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;

/**
 * Slice 1 of the concurrent load test ({@code docs/concurrent-load-test.md}) — the
 * <strong>in-JVM property-based tier</strong>. It drives randomized order-to-cash
 * sequences across the four product archetypes (to_stock / to_order ×
 * manufactured / purchased) and a pool of users through the <em>real</em> saga,
 * inbox handlers, and Jackson serde over the in-memory {@link World}, then asserts
 * the model-based <em>properties</em> hold for <em>every</em> generated mix:
 *
 * <ol>
 *   <li><b>Convergence</b> — every order's fulfilment saga reaches {@code COMPLETED}.</li>
 *   <li><b>No oversell</b> — no product's stock balance goes negative; {@code reserved}
 *       never exceeds {@code on_hand} (i.e. {@code available ≥ 0}).</li>
 *   <li><b>Double-entry</b> — every posted journal entry balances (Σ debits = Σ credits).</li>
 * </ol>
 *
 * <p>Each repetition is one randomized scenario seeded by its repetition index, so a
 * failure is reproducible (the seed is in every assertion message). Orders are driven
 * in phases — place all, secure supply for all, ship all, pay all — so many sagas are
 * in flight together rather than each completing before the next begins.
 *
 * <p><b>What this tier is and is not.</b> Because {@link World#settle()} is single-
 * threaded and each order owns its own product, this exercises saga / handler logic
 * under an arbitrary <em>mix and ordering</em> of orders — it is the property /
 * model-based tier, not the real shared-resource concurrency tier (that is the
 * Testcontainers + Gatling execution, {@code docs/concurrent-load-test.md} §2/§7).
 * Migration to jqwik (stateful Action sequences + shrinking) is the next slice.
 */
class OrderToCashConcurrentLoadPropertyTest {

    private static final BigDecimal SALES_PRICE = BigDecimal.valueOf(100);
    private static final BigDecimal SUPPLIER_PRICE = BigDecimal.valueOf(70);
    private static final BigDecimal SHIP_UNIT_COST = BigDecimal.valueOf(60);
    private static final BigDecimal WORK_ORDER_MINUTES = BigDecimal.TEN;

    /** The four product combinations the load test spans, plus the in-stock to_stock variant. */
    private enum Archetype {
        TO_STOCK_PURCHASED_INSTOCK,
        TO_STOCK_PURCHASED_SHORT,
        TO_STOCK_MANUFACTURED_SHORT,
        TO_ORDER_PURCHASED,
        TO_ORDER_MANUFACTURED
    }

    private record OrderPlan(String orderNumber, String customerCode, String productCode,
                             String productName, Archetype archetype, int qty) {}

    @RepeatedTest(30)
    void invariants_hold_across_a_randomized_order_to_cash_mix(RepetitionInfo info) {
        long seed = info.getCurrentRepetition();
        Random rng = new Random(seed);
        World world = new World();

        int customerCount = 3 + rng.nextInt(3);            // 3..5 distinct users
        for (int c = 0; c < customerCount; c++) {
            world.seedCustomer(customerCode(c), "Customer " + c);
        }

        int orderCount = 5 + rng.nextInt(8);               // 5..12 orders
        Archetype[] archetypes = Archetype.values();
        List<OrderPlan> plans = new ArrayList<>();
        for (int i = 0; i < orderCount; i++) {
            Archetype archetype = archetypes[rng.nextInt(archetypes.length)];
            String customer = customerCode(rng.nextInt(customerCount));
            int qty = 1 + rng.nextInt(4);                  // 1..4 units
            OrderPlan plan = new OrderPlan(
                "SO-" + seed + "-" + i, customer,
                "LP-" + seed + "-" + i, "Load product " + i, archetype, qty);
            seedProductFor(world, plan);
            plans.add(plan);
        }

        // Phase 1 — place every order (sagas park in flight: SUPPLY_SECURED or awaiting supply).
        for (OrderPlan plan : shuffled(plans, rng)) {
            world.placeOrder(plan.orderNumber(), plan.customerCode(),
                List.of(new World.OrderLineSpec(plan.productCode(), bd(plan.qty()))));
        }
        // Phase 2 — secure supply for every order (goods receipt / work-order completion / already reserved).
        for (OrderPlan plan : shuffled(plans, rng)) {
            secureSupply(world, plan);
        }
        // Phase 3 — ship every order.
        for (OrderPlan plan : shuffled(plans, rng)) {
            world.ship("SHIP-" + plan.orderNumber(), plan.orderNumber(),
                List.of(new World.ShipLineSpec(plan.productCode(), bd(plan.qty()), SHIP_UNIT_COST)));
        }
        // Phase 4 — pay every order in full.
        for (OrderPlan plan : shuffled(plans, rng)) {
            world.pay("PAY-" + plan.orderNumber(), plan.orderNumber(), SALES_PRICE.multiply(bd(plan.qty())));
        }

        assertConvergence(world, plans, seed);
        assertNoOversell(world, plans, seed);
        assertDoubleEntryBalances(world, seed);
    }

    // ── property assertions ───────────────────────────────────────────────

    private void assertConvergence(World world, List<OrderPlan> plans, long seed) {
        for (OrderPlan plan : plans) {
            assertThat(world.sagaState(plan.orderNumber()))
                .as("seed=%d: order %s (%s) must converge to COMPLETED", seed, plan.orderNumber(), plan.archetype())
                .isEqualTo(SalesOrderFulfilmentSaga.COMPLETED);
        }
    }

    private void assertNoOversell(World world, List<OrderPlan> plans, long seed) {
        Set<String> productCodes = new LinkedHashSet<>();
        plans.forEach(p -> productCodes.add(p.productCode()));
        for (String productCode : productCodes) {
            StockBalanceView balance = world.stockBalance(productCode);
            assertThat(balance.onHand())
                .as("seed=%d: %s on-hand must not go negative", seed, productCode)
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(balance.reserved())
                .as("seed=%d: %s reserved must not go negative", seed, productCode)
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(balance.available())
                .as("seed=%d: %s must not oversell (reserved ≤ on-hand)", seed, productCode)
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
    }

    private void assertDoubleEntryBalances(World world, long seed) {
        List<JournalEntry> entries = world.journalEntries();
        assertThat(entries)
            .as("seed=%d: completed orders must have posted journal entries", seed)
            .isNotEmpty();
        for (JournalEntry entry : entries) {
            BigDecimal debits = BigDecimal.ZERO;
            BigDecimal credits = BigDecimal.ZERO;
            for (JournalEntryLine line : entry.lines()) {
                debits = debits.add(line.debitAmount());
                credits = credits.add(line.creditAmount());
            }
            assertThat(debits.setScale(2, RoundingMode.HALF_UP))
                .as("seed=%d: journal %s must balance (debits=%s credits=%s)",
                    seed, entry.journalNumber(), debits, credits)
                .isEqualByComparingTo(credits.setScale(2, RoundingMode.HALF_UP));
        }
    }

    // ── per-archetype seeding + supply ────────────────────────────────────

    private void seedProductFor(World world, OrderPlan plan) {
        String code = plan.productCode();
        world.seedProduct(code, plan.productName(), SALES_PRICE);
        switch (plan.archetype()) {
            case TO_STOCK_PURCHASED_INSTOCK -> {
                world.markPurchasedSupply(code, SUPPLIER_PRICE);
                world.seedStock(code, bd(plan.qty()));          // ample: reserves from the pool at placement
            }
            case TO_STOCK_PURCHASED_SHORT ->
                world.markPurchasedSupply(code, SUPPLIER_PRICE); // no stock: shortage → purchasing → top up
            case TO_STOCK_MANUFACTURED_SHORT -> {
                world.markManufactured(code);                    // no stock: shortage → manufacturing → top up
                seedBillOfMaterials(world, plan);
            }
            case TO_ORDER_PURCHASED ->
                world.markPurchasedToOrder(code, SUPPLIER_PRICE); // pegged → purchasing
            case TO_ORDER_MANUFACTURED -> {
                world.markManufacturedToOrder(code);              // pegged → manufacturing
                seedBillOfMaterials(world, plan);
            }
        }
    }

    private void seedBillOfMaterials(World world, OrderPlan plan) {
        String rawCode = "RM-" + plan.productCode();
        world.seedRawMaterial(rawCode, "Raw for " + plan.productCode());
        world.seedBom(plan.productCode(), List.of(new World.BomLineSpec(rawCode, BigDecimal.ONE, false)));
        world.seedSingleOpRouting(plan.productCode());
        world.seedStock(rawCode, bd(plan.qty()).add(BigDecimal.TEN));   // surplus raw stock for the work order
    }

    private void secureSupply(World world, OrderPlan plan) {
        switch (plan.archetype()) {
            case TO_STOCK_PURCHASED_INSTOCK -> {
                // Already reserved from the pool at placement — nothing to do.
            }
            case TO_STOCK_PURCHASED_SHORT, TO_ORDER_PURCHASED ->
                world.receiveGoodsForReplenishment(plan.productCode(), "GR-" + plan.orderNumber());
            case TO_STOCK_MANUFACTURED_SHORT, TO_ORDER_MANUFACTURED ->
                world.completeWorkOrder(plan.productCode(), WORK_ORDER_MINUTES);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static String customerCode(int index) {
        return "CUST-" + index;
    }

    private static BigDecimal bd(int value) {
        return BigDecimal.valueOf(value);
    }

    private static List<OrderPlan> shuffled(List<OrderPlan> plans, Random rng) {
        List<OrderPlan> copy = new ArrayList<>(plans);
        Collections.shuffle(copy, rng);
        return copy;
    }
}
