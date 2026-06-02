package com.northwood.sales.domain;

import com.northwood.shared.domain.Assert;

/**
 * Commercial payment terms attached to a customer (as their default) and
 * snapshotted onto each sales order at placement (overridable per-order).
 *
 * <p>Hosted in {@code sales-events} (not {@code sales-service}) because it is
 * the cross-service contract carried on the wire format of
 * {@code sales.SalesOrderPlaced} and {@code sales.SalesOrderShipped}
 * ({@code paymentTerms}) — consuming services branch on the value (finance
 * auto-settles cash-on-delivery at shipment; inventory gates shipment for
 * prepayment) and need the same typed enum source, switching over the values
 * rather than string literals. Same pattern as {@code product.ProductType} /
 * {@code product.ValuationClass}. The string values must match the schema
 * CHECKs on {@code sales.customer.default_payment_terms},
 * {@code sales.sales_order_header.payment_terms}, and
 * {@code inventory.sales_order_line_facts.payment_terms} exactly.
 *
 * <ul>
 *   <li>{@link #ON_SHIPMENT} — credit terms; invoice created from the shipment,
 *       customer pays against it (Northwood's existing AR flow; the default).</li>
 *   <li>{@link #PREPAYMENT} — cash-with-order; invoiced at placement,
 *       shipment gated on full payment.</li>
 *   <li>{@link #CASH_ON_DELIVERY} — cash-on-delivery; invoice + full
 *       payment auto-recorded at shipment.</li>
 *   <li>{@link #DEPOSIT} — deposit / part-payment; a per-order
 *       {@code deposit_percent} is invoiced + paid up front, the balance is
 *       invoiced at shipment.</li>
 * </ul>
 *
 * <p><b>Naming.</b> Deliberately plural — the codebase's enums are otherwise
 * singular ({@code ProductType}, {@code Payment.Method}, {@code …Status}), but
 * "payment terms" is the established commercial noun phrase (a customer is
 * offered <i>payment terms</i>), and the name matches the {@code paymentTerms}
 * wire field + the {@code payment_terms} columns it mirrors. Kept plural for
 * that ubiquitous-language fit rather than renamed to {@code PaymentTerm} for
 * enum-naming uniformity.
 */
public enum PaymentTerms {
    ON_SHIPMENT("on_shipment"),
    PREPAYMENT("prepayment"),
    CASH_ON_DELIVERY("cash_on_delivery"),
    DEPOSIT("deposit");

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
