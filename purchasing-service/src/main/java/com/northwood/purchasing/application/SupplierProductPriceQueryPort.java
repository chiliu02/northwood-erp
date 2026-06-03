package com.northwood.purchasing.application;

import com.northwood.purchasing.application.dto.SupplierPriceListView;
import java.util.List;

/**
 * CQRS read port for the global supplier-price list. Joins
 * {@code supplier_product_price} to the supplier + product-card name snapshots
 * so the list view carries human-readable columns. Separate from
 * {@link com.northwood.purchasing.domain.SupplierProductPriceRepository}
 * because this is a cross-table read projection, not aggregate orchestration.
 */
public interface SupplierProductPriceQueryPort {

    /** Every supplier price, enriched with supplier + product names, supplier/product ordered. */
    List<SupplierPriceListView> findAll();
}
