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
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Slice 5.2 of the concurrent load test ({@code docs/concurrent-load-test.md}) — the
 * <strong>in-JVM property-based tier</strong>, now driven by <b>jqwik</b> so failing
 * scenarios <em>shrink</em> to the minimal reproducing mix (§6.1).
 *
 * <p>jqwik generates a random list of order specs — each a pick of the four product
 * archetypes (to_stock / to_order × manufactured / purchased, plus the in-stock
 * variant), a user from a pool, and a quantity. Every generated mix is driven through
 * the <em>real</em> saga + inbox handlers + Jackson serde over a fresh in-memory
 * {@link World}, then the model-based <em>properties</em> must hold:
 *
 * <ol>
 *   <li><b>Convergence</b> — every order's fulfilment saga reaches {@code COMPLETED}.</li>
 *   <li><b>No oversell</b> — no product's stock balance goes negative; {@code reserved}
 *       never exceeds {@code on_hand} (i.e. {@code available ≥ 0}).</li>
 *   <li><b>Double-entry</b> — every posted journal entry balances (Σ debits = Σ credits).</li>
 * </ol>
 *
 * <p>Orders are driven in phases (place all → secure supply all → ship all → pay all),
 * each phase shuffled by a per-run seed, so many sagas are in flight together rather
 * than each completing before the next begins. On failure jqwik prints the shrunk
 * sample and its reproduction seed.
 *
 * <p><b>What this tier is and is not.</b> {@link World#settle()} is single-threaded and
 * each order owns its own product, so this exercises saga / handler logic under an
 * arbitrary <em>mix and ordering</em> of orders — the property / model-based tier, not
 * the real shared-resource concurrency tier (the Testcontainers + Gatling execution,
 * {@code docs/concurrent-load-test.md} §2/§7).
 */
class OrderToCashConcurrentLoadPropertyTest {

    private static final int CUSTOMER_POOL = 5;
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

    /** A generated order: which archetype, which user (0..CUSTOMER_POOL-1), and how many units. */
    private record OrderSpec(Archetype archetype, int customer, int qty) {}

    /** A resolved order ready to drive: its codes plus the generated spec. */
    private record OrderPlan(String orderNumber, String customerCode, String productCode,
                             String productName, Archetype archetype, int qty) {}

    @Property(tries = 100)
    void invariants_hold_across_a_randomized_order_to_cash_mix(@ForAll("orderMixes") List<OrderSpec> mix) {
        World world = new World();
        for (int c = 0; c < CUSTOMER_POOL; c++) {
            world.seedCustomer(customerCode(c), "Customer " + c);
        }

        List<OrderPlan> plans = new ArrayList<>();
        for (int i = 0; i < mix.size(); i++) {
            OrderSpec spec = mix.get(i);
            OrderPlan plan = new OrderPlan(
                "SO-" + i, customerCode(spec.customer() % CUSTOMER_POOL),
                "LP-" + i, "Load product " + i, spec.archetype(), spec.qty());
            seedProductFor(world, plan);
            plans.add(plan);
        }

        // A per-run RNG only shuffles the phase orderings; the *mix* is jqwik's generated input.
        Random shuffleRng = new Random(mix.hashCode());

        // Phase 1 — place every order (sagas park in flight: SUPPLY_SECURED or awaiting supply).
        for (OrderPlan plan : shuffled(plans, shuffleRng)) {
            world.placeOrder(plan.orderNumber(), plan.customerCode(),
                List.of(new World.OrderLineSpec(plan.productCode(), bd(plan.qty()))));
        }
        // Phase 2 — secure supply for every order (goods receipt / work-order completion / already reserved).
        for (OrderPlan plan : shuffled(plans, shuffleRng)) {
            secureSupply(world, plan);
        }
        // Phase 3 — ship every order.
        for (OrderPlan plan : shuffled(plans, shuffleRng)) {
            world.ship("SHIP-" + plan.orderNumber(), plan.orderNumber(),
                List.of(new World.ShipLineSpec(plan.productCode(), bd(plan.qty()), SHIP_UNIT_COST)));
        }
        // Phase 4 — pay every order in full.
        for (OrderPlan plan : shuffled(plans, shuffleRng)) {
            world.pay("PAY-" + plan.orderNumber(), plan.orderNumber(), SALES_PRICE.multiply(bd(plan.qty())));
        }

        assertConvergence(world, plans);
        assertNoOversell(world, plans);
        assertDoubleEntryBalances(world);
    }

    @Provide
    Arbitrary<List<OrderSpec>> orderMixes() {
        Arbitrary<Archetype> archetype = Arbitraries.of(Archetype.values());
        Arbitrary<Integer> customer = Arbitraries.integers().between(0, CUSTOMER_POOL - 1);
        Arbitrary<Integer> quantity = Arbitraries.integers().between(1, 4);
        Arbitrary<OrderSpec> order = Combinators.combine(archetype, customer, quantity).as(OrderSpec::new);
        return order.list().ofMinSize(3).ofMaxSize(12);
    }

    // ── property assertions ───────────────────────────────────────────────

    private void assertConvergence(World world, List<OrderPlan> plans) {
        for (OrderPlan plan : plans) {
            assertThat(world.sagaState(plan.orderNumber()))
                .as("order %s (%s, qty %d) must converge to COMPLETED", plan.orderNumber(), plan.archetype(), plan.qty())
                .isEqualTo(SalesOrderFulfilmentSaga.COMPLETED);
        }
    }

    private void assertNoOversell(World world, List<OrderPlan> plans) {
        Set<String> productCodes = new LinkedHashSet<>();
        plans.forEach(p -> productCodes.add(p.productCode()));
        for (String productCode : productCodes) {
            StockBalanceView balance = world.stockBalance(productCode);
            assertThat(balance.onHand())
                .as("%s on-hand must not go negative", productCode)
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(balance.reserved())
                .as("%s reserved must not go negative", productCode)
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(balance.available())
                .as("%s must not oversell (reserved ≤ on-hand)", productCode)
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
    }

    private void assertDoubleEntryBalances(World world) {
        List<JournalEntry> entries = world.journalEntries();
        assertThat(entries)
            .as("completed orders must have posted journal entries")
            .isNotEmpty();
        for (JournalEntry entry : entries) {
            BigDecimal debits = BigDecimal.ZERO;
            BigDecimal credits = BigDecimal.ZERO;
            for (JournalEntryLine line : entry.lines()) {
                debits = debits.add(line.debitAmount());
                credits = credits.add(line.creditAmount());
            }
            assertThat(debits.setScale(2, RoundingMode.HALF_UP))
                .as("journal %s must balance (debits=%s credits=%s)", entry.journalNumber(), debits, credits)
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
