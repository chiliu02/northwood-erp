package com.northwood.finance.domain;

import java.util.UUID;

public record CustomerInvoiceId(UUID value) {

    public static CustomerInvoiceId newId() {
        return new CustomerInvoiceId(UUID.randomUUID());
    }

    public static CustomerInvoiceId of(UUID value) {
        return new CustomerInvoiceId(value);
    }
}
