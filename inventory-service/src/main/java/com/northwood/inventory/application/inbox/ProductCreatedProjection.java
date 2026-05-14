package com.northwood.inventory.application.inbox;

import java.util.UUID;

/**
 * Inserts an {@code inventory.stock_item} stub row so subsequent
 * {@code ReorderPolicyChanged} (and other product-master events) have a row
 * to project onto. §1F.2: closes the race where a product registered after
 * boot via {@code POST /api/products} would have its {@code ReorderPolicyChanged}
 * arrive at {@link StockItemProjection#applyReorderPolicy} and silently
 * no-op for lack of a row.
 *
 * <p>Insert-only, race-tolerant: {@code ON CONFLICT (product_id) DO NOTHING}.
 * If a stub already exists (out-of-order delivery, or the row was minted by
 * the Liquibase seed for one of the demo SKUs), this is a no-op.
 *
 * <p><b>UOM default.</b> {@code ProductCreated} doesn't carry the base UOM
 * (the producer's Product aggregate has {@code baseUomId}, but the event
 * payload omits it). The stub defaults to {@code 'EA'} matching every
 * existing seed row; a future event that does carry the UOM (or an inventory
 * command that authors it) can update via the aggregate.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductCreatedProjection}. Separate
 * from {@link StockItemProjection} (which writes via the aggregate repository
 * for reorder-policy mutations) because the stub creation has no aggregate
 * invariants to enforce — a direct INSERT with a sane default UOM is the
 * minimum-viable plumbing.
 */
public interface ProductCreatedProjection {

    void apply(UUID productId, String sku, String name, String productType);
}
