package com.northwood.sales.application.inbox;

import java.util.UUID;

/**
 * Inserts a stub {@code sales.product_card} row so subsequent product-master
 * events ({@link SalesPriceProjection#applySalesPrice} and
 * {@link ProductDiscontinuedProjection#applyDiscontinued}) have a row to
 * project onto. Mirrors the inventory and manufacturing seed-on-created
 * pattern, making the sales-side projection's lifetime structurally match the
 * Product aggregate's.
 *
 * <p>Insert-only, race-tolerant: {@code ON CONFLICT (product_id) DO NOTHING}.
 * The stub leaves {@code sales_price} and {@code currency_code} NULL —
 * {@code SalesOrderService.placeOrder} reads that as unsellable (caller must
 * either supply an explicit {@code unitPrice} or wait for
 * {@code SalesPriceChanged} to fill the row). A product that's created and
 * later discontinued without ever being priced never becomes sellable —
 * lifecycle: created (NULLs) → discontinued (NULLs + discontinued_at).
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductCreatedProjection}.
 */
public interface ProductCreatedProjection {

    void apply(UUID productId);
}
