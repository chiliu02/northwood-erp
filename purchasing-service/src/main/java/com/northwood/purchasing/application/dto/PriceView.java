package com.northwood.purchasing.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Application-layer read shape returned by
 * {@code SupplierProductPriceService.listForSupplier}. Decouples the
 * controller/DTO from the repository's internal {@code PriceRow} record
 * (which is a JDBC-shape implementation detail of
 * {@code SupplierProductPriceRepository}).
 */
public record PriceView(
    UUID supplierProductPriceId,
    UUID supplierId,
    UUID productId,
    String currencyCode,
    BigDecimal unitPrice,
    long version
) {}
