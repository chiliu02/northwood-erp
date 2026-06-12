package com.northwood.finance.application;

/**
 * Chart-of-accounts code aliases used by {@link JournalEntryService} and its
 * collaborators when building GL postings. Each constant names the account by
 * its <i>role</i> in finance's posting policy; the value is the code stored in
 * {@code finance.gl_account.code}.
 *
 * <p>These are <b>reference-data identifiers</b>, not enumerated states — they
 * fail every criterion that would justify an enum-with-{@code code()}
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

    /**
     * 2110 — Customer Deposits (liability). Credited on customer-payment
     * receipt for a {@code prepayment} invoice (Dr Cash); debited at shipment
     * to reclassify the deposit against Sales Revenue once the
     * goods-delivered performance obligation is met.
     */
    static final String CUSTOMER_DEPOSITS = "2110";

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

    /**
     * 1230 — Work In Progress. Raw materials Dr here when issued to a work
     * order (Cr 1210); the finished good Dr's 1220 / Cr's 1230 at completion.
     * Nets to zero per WO at standard cost (material-only cut).
     */
    static final String WIP = "1230";

    /**
     * 5250 — Conversion Cost Applied. Credited when a work order's standard
     * conversion cost (labour + overhead) is absorbed into WIP at completion
     * (Dr 1230 WIP), so WIP nets to zero against the full standard cost out
     * (material + conversion). The "applied" half of standard conversion costing
     * — Northwood doesn't post the actual labour/overhead it offsets, so this
     * carries the absorbed conversion that flows on into COGS at shipment
     * (dev-todo §2.42).
     */
    static final String CONVERSION_APPLIED = "5250";

    /**
     * 5100 — Production Variance. Carries the manufacturing efficiency variance
     * (actual − standard conversion) cleared off WIP at work-order completion:
     * unfavorable (actual &gt; standard) Dr's here / Cr WIP; favorable Dr's WIP /
     * Cr here. Keeps WIP netting to zero against the standard-cost FG receipt
     * (dev-todo §2.42 slice D).
     */
    static final String PRODUCTION_VARIANCE = "5100";

    /** 1210 — Raw Materials Inventory; per-class breakdown of {@link #INVENTORY}. */
    static final String RM_INVENTORY = "1210";

    /** 1220 — Finished Goods Inventory; per-class breakdown of {@link #INVENTORY}. */
    static final String FG_INVENTORY = "1220";

    /** 5200 — Raw Materials COGS; per-class breakdown of {@link #COGS}. */
    static final String MATERIALS_COGS = "5200";

    /** 5400 — Inventory Adjustment; the gain/loss offset for manual stock adjustments. */
    static final String INVENTORY_ADJUSTMENT = "5400";

    /**
     * 5500 — Promotions / Samples Expense. The cost (NOT price) of goods shipped
     * free-of-charge (a zero-price sales line: free sample, promotion, warranty
     * replacement, 100%-discount). Routed here instead of {@link #COGS} so the
     * P&amp;L separates "cost of giveaways" from cost-of-actual-sales — the credit
     * side is still the inventory account (the goods left stock at cost either way).
     */
    static final String PROMOTIONS_EXPENSE = "5500";

    private FinanceAccountCodes() {}
}
