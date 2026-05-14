package com.northwood.purchasing.application.inbox;

import com.northwood.product.domain.ApprovedVendor;
import java.util.List;
import java.util.UUID;

/**
 * Maintains {@code purchasing.product_approved_vendor} from
 * {@code product.ApprovedVendorListChanged} events. Replace-all semantics
 * matching the event payload (which carries the full new list).
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductApprovedVendorProjection}.
 */
public interface ProductApprovedVendorProjection {

    void replaceFor(UUID productId, List<ApprovedVendor> vendors);
}
