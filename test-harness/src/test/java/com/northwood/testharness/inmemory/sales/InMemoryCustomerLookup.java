package com.northwood.testharness.inmemory.sales;

import com.northwood.sales.application.CustomerLookup;
import com.northwood.sales.domain.Customer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryCustomerLookup implements CustomerLookup {

    private final Map<String, CustomerSummary> byCode = new HashMap<>();

    public InMemoryCustomerLookup put(String customerCode, String customerName, Customer.Status status) {
        byCode.put(customerCode, new CustomerSummary(UUID.randomUUID(), customerCode, customerName, status));
        return this;
    }

    @Override
    public Optional<CustomerSummary> findByCode(String customerCode) {
        return Optional.ofNullable(byCode.get(customerCode));
    }
}
