package com.northwood.finance.application;

/**
 * Chart-of-accounts code aliases used by {@link JournalEntryService} and its
 * collaborators when building GL postings. Each constant names the account by
 * its <i>role</i> in finance's posting policy; the value is the code stored in
 * {@code finance.gl_account.code}.
 *
 * <p>These are <b>reference-data identifiers</b>, not enumerated states — they
 * fail every criterion that would justify an enum-with-{@code dbValue()}
 * (see {@code docs/conventions.md} → <i>What still uses string literals</i>).
 * Specifically: the chart of accounts is data in {@code finance.gl_account},
 * customer-configurable in any real ERP; the codes are opaque pass-through
 * arguments to {@link GlAccountLookup#byCode(String)} rather than discriminators
 * compared via {@code switch} / {@code .equals}; only finance-service touches
 * them. The shape this class settles is "named alias for a reference-data
 * lookup", which is the right model for SKUs, customer codes, supplier codes,
 * and other reference-data identifiers.
 *
 * <p>Package-private so callers stay inside {@code finance.application}; tests
 * in the same package can reference these for clarity, but most journal-line
 * assertions correctly pin to the wire-format value in the column rather than
 * to this alias.
 */
final class FinanceAccountCodes {

    /** 5000 — generic / finished-goods COGS. */
    static final String COGS = "5000";

    /** 2100 — Accounts Payable. */
    static final String AP = "2100";

    /** 1000 — operating bank account. */
    static final String BANK = "1000";

    /** 1100 — Accounts Receivable. */
    static final String AR = "1100";

    /** 4000 — sales revenue. */
    static final String REVENUE = "4000";

    /** 1200 — generic Inventory; fallback when a product has no valuation class. */
    static final String INVENTORY = "1200";

    /** 1300 — Goods Received Not Invoiced; clears between receipt and invoice approval. */
    static final String GRNI = "1300";

    /** 1210 — Raw Materials Inventory; per-class breakdown of {@link #INVENTORY}. */
    static final String RM_INVENTORY = "1210";

    /** 1220 — Finished Goods Inventory; per-class breakdown of {@link #INVENTORY}. */
    static final String FG_INVENTORY = "1220";

    /** 5200 — Raw Materials COGS; per-class breakdown of {@link #COGS}. */
    static final String MATERIALS_COGS = "5200";

    /** 5400 — Inventory Adjustment; the gain/loss offset for manual stock adjustments (§2.29). */
    static final String INVENTORY_ADJUSTMENT = "5400";

    private FinanceAccountCodes() {}
}
