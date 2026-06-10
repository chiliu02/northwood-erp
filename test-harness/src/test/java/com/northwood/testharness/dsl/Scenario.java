package com.northwood.testharness.dsl;

/**
 * Fluent entry point for an acceptance scenario (see {@code docs/dsl.md} §7).
 * Threads one {@link World} through a {@code given / when / then} script
 * written in the ubiquitous language:
 *
 * <pre>{@code
 * scenario("in-stock order: ships, invoices, and settles in full")
 *   .given(a_customer("CUST-001", "Acme Corp"))
 *   .and(a_product("FG-001", "Finished Good 1").pricedAt(money(100)))
 *   .when(customer("CUST-001").places_order("SO-9001").line("FG-001", qty(3)))
 *   .then(order("SO-9001").reaches(READY_TO_SHIP));
 * }</pre>
 *
 * <p>Each stage applies its step to the shared World immediately, so the chain
 * reads top-to-bottom as the requirement does. The infrastructure timing
 * ({@code advanceSagaWorker} / {@code bus.drain}) lives entirely inside
 * {@link World#settle()}, which every {@code when} action runs implicitly — the
 * scenario never names it.
 *
 * <p>The vocabulary ({@code a_customer}, {@code customer}, {@code order},
 * {@code money}, …) is the static API on {@link Dsl}; a scenario file static-
 * imports {@code Dsl.*} plus the real {@code WarehouseCodes} / saga-state
 * constants it asserts against. The DSL invents no business vocabulary — every
 * step resolves to a real kit operation on the World.
 */
public final class Scenario {

    /** Seeds the guard — a {@code given} / {@code and} fact about the world. */
    @FunctionalInterface
    public interface SeedStep {
        void seed(World world);
    }

    /** Drives a real domain action — a {@code when} trigger. Settles the world. */
    @FunctionalInterface
    public interface ActionStep {
        void act(World world);
    }

    /** Asserts an outcome — a {@code then} / {@code and} expectation. */
    @FunctionalInterface
    public interface AssertStep {
        void check(World world);
    }

    private final String description;
    private final World world = new World();

    private Scenario(String description) {
        this.description = description;
    }

    /** Begin a scenario. The description is the requirement, restated in one line. */
    public static Scenario scenario(String description) {
        return new Scenario(description);
    }

    public String description() {
        return description;
    }

    // ── given / and-seed (the guard) ──

    public Scenario given(SeedStep step) {
        step.seed(world);
        return this;
    }

    public Scenario and(SeedStep step) {
        step.seed(world);
        return this;
    }

    // ── when (the trigger) ──

    public Scenario when(ActionStep step) {
        step.act(world);
        return this;
    }

    // ── then / and-assert (the outcome) ──

    public Scenario then(AssertStep step) {
        step.check(world);
        return this;
    }

    public Scenario and(AssertStep step) {
        step.check(world);
        return this;
    }
}
