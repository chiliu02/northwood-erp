package com.northwood.shared.domain;

/**
 * Named wire-format constants for the ISO 4217 currency codes the codebase
 * actually references. Use these instead of literal {@code "AUD"} strings
 * at every site that constructs a {@link Money}, names a transaction currency
 * on an event payload, or pins an expected value in a test.
 *
 * <p>Deliberately constants rather than an enum. ISO 4217 defines 180+ codes
 * and the JDK already ships {@link java.util.Currency} for the runtime
 * lookup, so locking the domain to a closed set buys nothing while making
 * inbound external strings awkward (every {@code Currency.valueOf} would
 * need an explicit catch). The constants are a compile-time anchor for the
 * three values our code actually mentions today — adding a fourth is one
 * line here, no enum migration.
 *
 * <p>Why a {@code Currencies} (plural) constants-holder rather than a method
 * on {@link Money}: this follows the project's "Cross-service wire-format
 * constants" rule (see {@code CLAUDE.md}). Wire-format values that any
 * service might read or write get a dedicated {@code XxxStatuses}-shaped
 * holder; placing them on a domain VO would imply per-instance state.
 */
public final class Currencies {

    /** Australian dollar — the company's base currency for every showcase scenario. */
    public static final String AUD = "AUD";

    /** US dollar — referenced today only by tests exercising the cross-currency rejection path on {@link Money}. */
    public static final String USD = "USD";

    /**
     * New Zealand dollar — schema-prep for the NZ-customer scenarios sketched
     * alongside {@code finance.tax_code = 'GST_NZ_15'}. No Java code path
     * produces or consumes it today.
     */
    public static final String NZD = "NZD";

    private Currencies() {
        // Constants-holder; not instantiable.
    }
}
