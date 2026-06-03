package com.northwood.product.application.dto;

import com.northwood.product.domain.ApprovedVendor;
import java.util.UUID;

/**
 * Read-side projection of a {@link ApprovedVendor} child row for the wire layer.
 * Returned by {@code GET /api/products/{id}/approved-vendors} so the catalog UI
 * can render and edit the approved-vendor list (the PUT counterpart takes the
 * {@code ApprovedVendorCommand} shape).
 */
public record ApprovedVendorView(
    UUID supplierId,
    String supplierCode,
    String supplierName,
    boolean preferred
) {
    public static ApprovedVendorView from(ApprovedVendor v) {
        return new ApprovedVendorView(v.supplierId(), v.supplierCode(), v.supplierName(), v.preferred());
    }
}
