package com.northwood.manufacturing.application;

import java.util.UUID;

/**
 * Read port for "is this product discontinued?" — narrow, single-method
 * lookup used by {@link BomEditService#addLine} to reject new BOM lines
 * that name a discontinued component (the manufacturing twin of
 * purchasing's {@code DiscontinuedProductLookup}).
 *
 * <p>Backed by {@code manufacturing.product_replenishment.discontinued_at}
 * (set by {@code ProductDiscontinuedHandler}). Distinct signal from the
 * {@code is_purchased=false AND is_manufactured=false} pair, which can
 * also occur on a never-classified row.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcDiscontinuedProductLookup}.
 */
public interface DiscontinuedProductLookup {

    boolean isDiscontinued(UUID productId);
}
