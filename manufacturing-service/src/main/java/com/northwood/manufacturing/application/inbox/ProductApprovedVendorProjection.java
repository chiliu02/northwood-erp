package com.northwood.manufacturing.application.inbox;

import com.northwood.product.domain.ApprovedVendor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Maintains {@code manufacturing.product_approved_vendor} from
 * {@code product.ApprovedVendorListChanged} events. Replace-all semantics
 * matching the event payload (which carries the full new list).
 *
 * <p>Read path: {@link #findPreferredSupplierId(UUID)} returns the unique
 * preferred supplier for a product, or {@link Optional#empty()} when there
 * is no preferred (no rows / zero preferred / multiple preferred). The
 * rollup engine treats {@code Optional.empty()} as one of the
 * "inputs missing" cases — see {@code MaterialsCostRollupService}.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductApprovedVendorProjection}.
 */
public interface ProductApprovedVendorProjection {

    void replaceFor(UUID productId, List<ApprovedVendor> vendors);

    Optional<UUID> findPreferredSupplierId(UUID productId);
}
