package com.northwood.purchasing.application;

import com.northwood.purchasing.domain.SupplierId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side port over the supplier price list. Looked up by
 * {@code PurchaseOrderService} when converting a requisition to a PO so
 * each PO line gets a real {@code unit_price}. Supports effective-date
 * ranges and tiered quantity breaks: the implementation picks the row
 * whose {@code [effective_from, effective_to)} window covers {@code at}
 * and has the highest {@code min_quantity ≤ quantity}.
 */
public interface SupplierProductPriceLookup {

    /**
     * Backward-compat: looks up the price effective today at
     * {@code quantity = 0} (base tier). Equivalent to
     * {@link #findUnitPrice(SupplierId, UUID, String, LocalDate, BigDecimal)}
     * with {@code at = today, quantity = ZERO}.
     */
    Optional<BigDecimal> findUnitPrice(SupplierId supplierId, UUID productId, String currencyCode);

    /**
     * Find the unit price effective on {@code at} for the given
     * {@code quantity}. Picks the highest {@code min_quantity ≤ quantity}
     * tier whose effective range covers {@code at}.
     */
    Optional<BigDecimal> findUnitPrice(
        SupplierId supplierId,
        UUID productId,
        String currencyCode,
        LocalDate at,
        BigDecimal quantity
    );
}
