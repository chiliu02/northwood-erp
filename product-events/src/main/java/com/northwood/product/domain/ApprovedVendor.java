package com.northwood.product.domain;

import java.util.UUID;

/**
 * One supplier approved to supply a product. Lives on the product aggregate
 * as a child collection (separate table {@code product.approved_vendor}); the
 * collection mutates as a whole via
 * {@link ApprovedVendorRepository#replaceFor} and is emitted in full on
 * {@code product.ApprovedVendorListChanged}.
 *
 * <p>Domain VO — kept here in {@code domain/} rather than nested on the event
 * class so repository ports + adapters don't need to import {@code events.*}.
 * The event references the VO, not the other way around.
 */
public record ApprovedVendor(
    UUID supplierId,
    String supplierCode,
    String supplierName,
    boolean preferred
) {}
