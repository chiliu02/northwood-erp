package com.northwood.purchasing.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@code purchasing.supplier_product_price} — the data of
 * record for the per-supplier price list. Not a CQRS projection (which is
 * upsert-only); this is the authoritative table.
 *
 * <p>Light-aggregate shape: each row keyed on
 * {@code (supplier_id, product_id, currency_code)}. The application service
 * decides insert-vs-update semantics + emits the change event.
 */
public interface SupplierProductPriceRepository {

    /** Look up the existing row for a (supplier, product, currency) tuple. */
    Optional<ExistingPrice> find(UUID supplierId, UUID productId, String currencyCode);

    /** Insert a new row with the supplied PK. */
    void insert(UUID priceId, UUID supplierId, UUID productId, String currencyCode, BigDecimal unitPrice);

    /** Update an existing row's price + bump version. */
    void updatePrice(UUID priceId, BigDecimal newUnitPrice);

    /** List rows for a supplier (read-side for authoring UIs). */
    List<PriceRow> listForSupplier(UUID supplierId);

    /** Carries just the bits needed to decide upsert behaviour. */
    record ExistingPrice(UUID priceId, BigDecimal unitPrice) {}

    /** Full read-side row. */
    record PriceRow(
        UUID supplierProductPriceId,
        UUID supplierId,
        UUID productId,
        String currencyCode,
        BigDecimal unitPrice,
        long version
    ) {}
}
