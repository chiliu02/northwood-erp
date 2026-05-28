package com.northwood.sales.application;

import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.PaymentTerms;
import java.util.Optional;
import java.util.UUID;

/**
 * Lightweight read port for the {@code sales.customer} table. Sales orders
 * carry denormalised {@code customer_code} / {@code customer_name} for
 * read-model independence, so we resolve them at order-placement time.
 *
 * <p>{@code status} is included so {@code SalesOrderService.placeOrder}
 * can reject orders against deactivated/blocked customers — see the
 * Customer aggregate's lifecycle. Stays a narrow {@code *Lookup} (single
 * field-resolution call) rather than promoted to a Repository: place-order
 * doesn't need addresses or contact info.
 */
public interface CustomerLookup {

    Optional<CustomerSummary> findByCode(String customerCode);

    record CustomerSummary(
        UUID customerId, String customerCode, String customerName,
        Customer.Status status, PaymentTerms defaultPaymentTerms
    ) {}
}
