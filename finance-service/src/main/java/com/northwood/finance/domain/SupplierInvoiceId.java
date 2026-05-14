package com.northwood.finance.domain;

import java.util.UUID;

public record SupplierInvoiceId(UUID value) {

    public static SupplierInvoiceId newId() {
        return new SupplierInvoiceId(UUID.randomUUID());
    }

    public static SupplierInvoiceId of(UUID value) {
        return new SupplierInvoiceId(value);
    }
}
