package com.northwood.testharness.inmemory.purchasing;

import com.northwood.product.domain.ApprovedVendor;
import com.northwood.purchasing.application.ApprovedVendorQueryPort;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class InMemoryApprovedVendorQueryPort implements ApprovedVendorQueryPort {

    private final Map<UUID, List<ApprovedVendor>> byProductId = new HashMap<>();

    public InMemoryApprovedVendorQueryPort putPreferred(UUID productId, UUID supplierId, String supplierCode, String supplierName) {
        byProductId.computeIfAbsent(productId, k -> new ArrayList<>())
            .add(new ApprovedVendor(supplierId, supplierCode, supplierName, true));
        return this;
    }

    @Override
    public List<ApprovedVendor> findApprovedFor(UUID productId) {
        List<ApprovedVendor> list = byProductId.get(productId);
        if (list == null) return List.of();
        // Preferred first
        List<ApprovedVendor> out = new ArrayList<>();
        for (ApprovedVendor v : list) if (v.preferred()) out.add(v);
        for (ApprovedVendor v : list) if (!v.preferred()) out.add(v);
        return out;
    }
}
