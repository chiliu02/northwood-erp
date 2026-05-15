package com.northwood.product.domain;

import java.util.UUID;

/**
 * One supplier approved to supply a product. Child collection on the
 * {@code Product} aggregate (denormalised into {@code product.approved_vendor}
 * for persistence); mutated as a whole via {@code Product.setApprovedVendors}
 * and emitted in full on {@code product.ApprovedVendorListChanged}.
 *
 * <p>Domain VO — kept here in {@code domain/} rather than nested on the event
 * class so the aggregate + repository can reference it without importing
 * {@code events.*}. The event references the VO, not the other way around.
 */
public record ApprovedVendor(
    UUID supplierId,
    String supplierCode,
    String supplierName,
    boolean preferred
) {}
