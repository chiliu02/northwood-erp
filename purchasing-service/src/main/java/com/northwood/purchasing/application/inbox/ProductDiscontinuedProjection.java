package com.northwood.purchasing.application.inbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Inserts into {@code purchasing.product_card} so the read-side
 * {@link com.northwood.purchasing.application.DiscontinuedProductLookup}
 * can gate new requisition / PO creation. Upsert semantics; a redelivered
 * event simply re-stamps the timestamp.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductDiscontinuedProjection}.
 */
public interface ProductDiscontinuedProjection {

    void applyDiscontinued(UUID productId, Instant discontinuedAt);
}
