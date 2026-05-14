package com.northwood.purchasing.application;

import java.util.UUID;

/**
 * Narrow operational read over {@code purchasing.product_discontinued}.
 * Used by {@code PurchaseRequisitionService} (and, defensively, by
 * {@code PurchaseOrderService.convertFromRequisition}) to reject new
 * commitments to products that product-service has retired.
 *
 * <p>Single-method shape → {@code *Lookup} per the project's port-naming
 * vocabulary (vs {@code *QueryPort}, which returns wider row shapes).
 */
public interface DiscontinuedProductLookup {

    boolean isDiscontinued(UUID productId);
}
