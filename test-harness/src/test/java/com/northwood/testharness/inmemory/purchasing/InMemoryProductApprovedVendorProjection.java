package com.northwood.testharness.inmemory.purchasing;

import com.northwood.product.domain.ApprovedVendor;
import com.northwood.purchasing.application.inbox.ProductApprovedVendorProjection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class InMemoryProductApprovedVendorProjection implements ProductApprovedVendorProjection {

    private final Map<UUID, List<ApprovedVendor>> byProductId = new HashMap<>();

    @Override
    public void replaceFor(UUID productId, List<ApprovedVendor> vendors) {
        byProductId.put(productId, vendors == null ? List.of() : List.copyOf(vendors));
    }
}
