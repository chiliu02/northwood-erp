package com.northwood.sales.domain;

import com.northwood.shared.domain.Assert;

/**
 * Commercial payment terms attached to a customer (as their default) and
 * snapshotted onto each sales order at placement (overridable per-order).
 *
 * <p>The default {@link #ON_SHIPMENT} matches Northwood's existing credit-terms
 * AR flow — the invoice is created from the shipment, customer pays against
 * it. {@link #PREPAYMENT} (cash-with-order) drives a different saga branch
 * (§2.31): the customer is invoiced at order placement and shipment is gated
 * on full payment. Mirrors the {@code dbValue()} / {@code fromDb()} shape used
 * by other sales enums (e.g. {@link Customer.Status}, {@link SalesOrder.Status}).
 *
 * <p>Wire-format values exposed via {@link #dbValue()} are stored in
 * {@code sales.customer.default_payment_terms} and
 * {@code sales.sales_order_header.payment_terms} (CHECK constraints mirror the
 * enum), and ride on the {@code sales.SalesOrderPlaced} event payload as
 * {@code paymentTerms} so reporting can project them onto
 * {@code reporting.sales_order_360_view.payment_terms}.
 */
public enum PaymentTerms {
    /** Credit terms — invoice created from shipment, customer pays against it (the default). */
    ON_SHIPMENT("on_shipment"),
    /** Cash-with-order — invoice created at placement, shipment gated on full payment. */
    PREPAYMENT("prepayment");

    private final String dbValue;

    PaymentTerms(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static PaymentTerms fromDb(String value) {
        for (PaymentTerms t : values()) {
            if (t.dbValue.equals(value)) return t;
        }
        throw Assert.unknownValue("payment_terms", value);
    }
}
