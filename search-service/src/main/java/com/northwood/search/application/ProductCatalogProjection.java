package com.northwood.search.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Write side of the {@code products} Elasticsearch read model. The inbox
 * handlers in {@code application/inbox/} call these methods as product events
 * arrive; the Elasticsearch implementation lives in
 * {@code infrastructure/elasticsearch/}.
 *
 * <p>Every method is idempotent — the document id is the product's aggregate id,
 * so an at-least-once redelivery upserts the same document with the same effect.
 * Order-tolerant by design: a partial update that arrives before the document
 * exists (e.g. a price change racing ahead of {@code ProductCreated}) upserts a
 * stub the later create backfills.
 */
public interface ProductCatalogProjection {

    /** Index (or refresh the identity of) a product from {@code ProductCreated}. */
    void upsertProduct(UUID productId, String sku, String name, String productType, Instant occurredAt);

    /** Partial update of the sales price from {@code SalesPriceChanged}. */
    void updateSalesPrice(UUID productId, BigDecimal salesPrice, String currencyCode);

    /** Flag the product discontinued (status facet) from {@code ProductDiscontinued}. */
    void markDiscontinued(UUID productId);
}
