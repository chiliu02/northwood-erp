package com.northwood.sales.application.dto;

import com.northwood.sales.domain.Customer;
import java.util.UUID;

/** Read-side projection of {@link Customer} for the wire layer. */
public record CustomerView(
    UUID customerId,
    String customerCode,
    String name,
    String email,
    String phone,
    String billingAddress,
    String shippingAddress,
    String status,
    long version
) {
    public static CustomerView from(Customer c) {
        return new CustomerView(
            c.id().value(),
            c.customerCode(),
            c.name(),
            c.email(),
            c.phone(),
            c.billingAddress(),
            c.shippingAddress(),
            c.status().name().toLowerCase(),
            c.version()
        );
    }
}
