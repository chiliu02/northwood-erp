package com.northwood.testharness.inmemory.sales;

import com.northwood.sales.application.CustomerLookup;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.PaymentTerms;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryCustomerLookup implements CustomerLookup {

    private final Map<String, CustomerSummary> byCode = new HashMap<>();

    public InMemoryCustomerLookup put(String customerCode, String customerName, Customer.Status status) {
        return put(customerCode, customerName, status, PaymentTerms.ON_SHIPMENT);
    }

    /** §2.31 Slice A: overload that lets tests seed a customer with explicit payment terms. */
    public InMemoryCustomerLookup put(String customerCode, String customerName, Customer.Status status, PaymentTerms defaultPaymentTerms) {
        byCode.put(customerCode, new CustomerSummary(UUID.randomUUID(), customerCode, customerName, status, defaultPaymentTerms));
        return this;
    }

    @Override
    public Optional<CustomerSummary> findByCode(String customerCode) {
        return Optional.ofNullable(byCode.get(customerCode));
    }
}
