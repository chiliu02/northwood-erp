package com.northwood.product.domain;

import java.util.List;
import java.util.Optional;

/**
 * Domain-side repository contract. The infrastructure layer provides the
 * implementation that talks to PostgreSQL; the domain only knows this interface.
 */
public interface ProductRepository {

    Optional<Product> findById(ProductId id);

    Optional<Product> findBySku(String sku);

    /** All products, ordered by SKU. Used by the demo UI catalog list. */
    List<Product> findAll();

    /**
     * Persist a product and any pending events (the events go to the outbox in
     * the same transaction). Implementations must use optimistic concurrency
     * via the version column.
     */
    void save(Product product);
}
