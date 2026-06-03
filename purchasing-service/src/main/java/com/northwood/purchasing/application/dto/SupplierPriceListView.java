package com.northwood.purchasing.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Enriched read shape for the global supplier-price list — the
 * {@code supplier_product_price} row joined to the supplier (name + code,
 * local to purchasing) and the {@code product_card} name snapshot (sku + name,
 * projected from {@code product.ProductCreated}). Lets the UI list every price
 * with human-readable supplier + product columns rather than raw UUIDs.
 *
 * <p>{@code supplierName} / {@code productSku} / {@code productName} are
 * nullable — a supplier with no card name yet (cold-start before the
 * ProductCreated projection lands) LEFT JOINs to null rather than dropping the
 * price row.
 */
public record SupplierPriceListView(
    UUID supplierProductPriceId,
    UUID supplierId,
    String supplierCode,
    String supplierName,
    UUID productId,
    String productSku,
    String productName,
    String currencyCode,
    BigDecimal unitPrice,
    BigDecimal minQuantity,
    long version
) {}
