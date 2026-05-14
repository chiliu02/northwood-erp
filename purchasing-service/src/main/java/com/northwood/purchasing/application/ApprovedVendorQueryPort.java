package com.northwood.purchasing.application;

import com.northwood.product.domain.ApprovedVendor;
import java.util.List;
import java.util.UUID;

/**
 * Read-side port over {@code purchasing.product_approved_vendor}, the
 * Shape A consumer projection of product master's approved-vendor list.
 * Used by {@code PurchaseOrderService.pickSupplier} to gate supplier
 * selection on the engineering-approved list.
 *
 * <p>Returns {@link ApprovedVendor} from {@code product-events} — the row
 * shape is a 1:1 cache of the upstream {@code product.ApprovedVendorListChanged}
 * payload, so the producer's VO is reused directly rather than mirrored on the
 * consumer side. If purchasing's read needs ever diverge from the wire shape
 * (extra fields, narrower types), introduce a consumer-side record then.
 */
public interface ApprovedVendorQueryPort {

    /** All approved vendors for a single product, preferred ones first. */
    List<ApprovedVendor> findApprovedFor(UUID productId);
}
