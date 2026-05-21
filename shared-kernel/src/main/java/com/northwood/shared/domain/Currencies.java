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

    /**
     * The showcase base currency — today {@link #AUD}. Use this where the
     * intent is "whatever the company's default currency is" rather than
     * "Australian dollar specifically": REST {@code defaultValue} annotations,
     * domain factories with no per-row currency column, inbox handlers whose
     * upstream event lacks a {@code currencyCode} field, and {@link #orBase}'s
     * substitution. Tests that assert AUD-specific behaviour or pass through
     * a USD fixture for cross-currency rejection should still reference
     * {@link #AUD} / {@link #USD} directly — they're testing the codes, not
     * the company's default.
     *
     * <p>Made a separate symbol from {@link #AUD} so that "the base currency"
     * has a name independent of the value behind it. If the showcase ever
     * re-bases to USD or NZD, only this line changes.
     */
    public static final String BASE_CURRENCY = AUD;

    /**
     * Returns {@code currencyCode} when non-null, otherwise {@link #BASE_CURRENCY}.
     *
     * <p><b>Trigger.</b> A nullable {@code currencyCode} reaches a site that
     * needs to persist a non-null value — typically a projection writer
     * applying an inbox event whose payload's {@code currencyCode} field was
     * omitted (legacy event before the column existed, or an emitter that
     * didn't populate it), or a domain factory wrapping a request DTO that
     * left currency out.
     *
     * <p><b>Substitution.</b> {@link #BASE_CURRENCY} (today {@link #AUD}).
     * Every showcase scenario transacts in the base currency, so defaulting
     * silently produces correct downstream GL postings and reporting rollups.
     *
     * <p><b>Why a method, not an inline ternary at each site.</b> This is
     * the project's "null-coalescing-to-default" pattern documented in
     * {@code CLAUDE.md} → <i>Document silent fallbacks</i>. Centralising
     * the 18 inline ternaries that used to live across finance / sales /
     * purchasing / reporting domain + projection writers gives the rule
     * one rationale site (here), one row in {@code docs/design-notes.md},
     * and one place to flip the behaviour if the fallback ever needs to
     * tighten.
     *
     * <p><b>No runtime log.</b> Deliberately. Per {@code design-notes.md}
     * operating notes, a fallback log must carry entity-id context to be
     * useful, and this static helper has no context. The tightening
     * alternative below proposes threading caller context through; until
     * then, the fallback is silent.
     *
     * <p><b>Tightening alternatives.</b>
     * <ul>
     *   <li>Throw {@link NullPointerException} instead of returning AUD.
     *       Requires every upstream caller to populate {@code currencyCode}
     *       — feasible once event payloads + DTOs are audited.</li>
     *   <li>Take a {@code String contextDescription} parameter, log at
     *       DEBUG when the fallback fires. Adds verbosity at every site
     *       but lets ops correlate the fallback to the affected row.</li>
     * </ul>
     */
    public static String orBase(String currencyCode) {
        return currencyCode == null ? BASE_CURRENCY : currencyCode;
    }

    private Currencies() {
        // Constants-holder; not instantiable.
    }
}
