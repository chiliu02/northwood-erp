package com.northwood.finance.domain;

/**
 * Wire-format aggregate-type constants owned by finance-service. Single source
 * of truth for every {@code aggregate_type} string this service produces.
 *
 * <p>Producer-side (aggregate roots in {@code finance-service}) re-exports
 * each value as its own {@code AGGREGATE_TYPE} field for stable call sites.
 * Cross-service consumers (consumer test fixtures, cross-service event
 * stamping) import directly from this class — the {@code finance-events} jar
 * is the only cross-service contract surface for finance's wire constants.
 *
 * <p>Convention introduced 2026-05-16 (§2.20).
 */
public final class FinanceAggregateTypes {

    public static final String JOURNAL_ENTRY = "JournalEntry";
    public static final String CUSTOMER_INVOICE = "CustomerInvoice";
    public static final String SUPPLIER_INVOICE = "SupplierInvoice";
    public static final String PAYMENT = "Payment";

    private FinanceAggregateTypes() {}
}
