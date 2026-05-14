package com.northwood.testharness.inmemory.manufacturing;

import com.northwood.manufacturing.application.inbox.ProductApprovedVendorProjection;
import com.northwood.product.domain.ApprovedVendor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link ProductApprovedVendorProjection}. Replace-all per product.
 * Preferred-supplier read returns empty when there are zero or 2+ preferred
 * rows, mirroring the JDBC adapter.
 */
public final class InMemoryProductApprovedVendorProjection implements ProductApprovedVendorProjection {

    private final Map<UUID, List<ApprovedVendor>> byProductId = new HashMap<>();

    @Override
    public void replaceFor(UUID productId, List<ApprovedVendor> vendors) {
        byProductId.put(productId, vendors == null ? List.of() : List.copyOf(vendors));
    }

    @Override
    public Optional<UUID> findPreferredSupplierId(UUID productId) {
        List<ApprovedVendor> list = byProductId.get(productId);
        if (list == null) return Optional.empty();
        UUID preferred = null;
        int preferredCount = 0;
        for (ApprovedVendor v : list) {
            if (v.preferred()) {
                preferredCount++;
                preferred = v.supplierId();
            }
        }
        if (preferredCount != 1) return Optional.empty();
        return Optional.of(preferred);
    }

    /** Test-side: seed a single preferred supplier directly. */
    public InMemoryProductApprovedVendorProjection putPreferred(UUID productId, UUID supplierId) {
        byProductId.put(productId, List.of(
            new ApprovedVendor(supplierId, "SUP-" + supplierId.toString().substring(0, 8), "Supplier " + supplierId, true)
        ));
        return this;
    }
}
