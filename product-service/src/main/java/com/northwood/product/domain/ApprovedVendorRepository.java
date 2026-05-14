package com.northwood.product.domain;

import java.util.List;
import java.util.UUID;

/**
 * Read/write port over {@code product.approved_vendor}. Authoring path uses
 * {@link #replaceFor(UUID, List)} to swap the entire list for a product
 * atomically — matching the event semantics, which carry the full new list
 * (not deltas).
 */
public interface ApprovedVendorRepository {

    List<ApprovedVendor> findForProduct(UUID productId);

    void replaceFor(UUID productId, List<ApprovedVendor> vendors);
}
