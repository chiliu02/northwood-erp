package com.northwood.product.application.dto;

import java.util.UUID;

/**
 * Application-layer command shape for {@code ProductService.setApprovedVendors}.
 * Same field-shape as the domain VO {@code ApprovedVendor}, but lives in the
 * application layer so controllers don't need to import the domain type to
 * construct the input. {@code ProductService} maps to {@code ApprovedVendor}
 * internally before invoking the aggregate.
 */
public record ApprovedVendorCommand(
    UUID supplierId,
    String supplierCode,
    String supplierName,
    boolean preferred
) {}
