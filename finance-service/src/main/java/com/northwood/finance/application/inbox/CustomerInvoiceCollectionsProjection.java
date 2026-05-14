package com.northwood.finance.application.inbox;

import java.util.UUID;

/**
 * §1F.3: flags any outstanding customer invoice for the named customer so a
 * collections workflow can pick them up. "Outstanding" means
 * {@code status IN ('posted', 'partially_paid') AND outstanding_amount > 0} —
 * a draft is not yet a real receivable, and a {@code paid} / {@code cancelled}
 * invoice has nothing to collect.
 *
 * <p>Idempotent: setting {@code flagged_for_collections = true} on an already-
 * flagged row is a no-op; the redelivery path is therefore safe.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcCustomerInvoiceCollectionsProjection}.
 */
public interface CustomerInvoiceCollectionsProjection {

    /**
     * Flag every outstanding invoice for the customer.
     *
     * @return number of rows flagged (0 if the customer had no outstanding
     *     invoices — informational, not an error).
     */
    int flagOutstandingForCollections(UUID customerId);
}
