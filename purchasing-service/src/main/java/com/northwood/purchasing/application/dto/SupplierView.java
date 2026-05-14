package com.northwood.purchasing.application.dto;

import com.northwood.purchasing.domain.Supplier;
import java.util.UUID;

/** Read-side projection of {@link Supplier} for the wire layer. */
public record SupplierView(
    UUID supplierId,
    String supplierCode,
    String name,
    String status
) {
    public static SupplierView from(Supplier s) {
        return new SupplierView(
            s.id().value(),
            s.supplierCode(),
            s.name(),
            s.status()
        );
    }
}
