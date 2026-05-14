package com.northwood.testharness.inmemory.purchasing;

import com.northwood.purchasing.domain.Supplier;
import com.northwood.purchasing.domain.SupplierId;
import com.northwood.purchasing.domain.SupplierRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemorySupplierRepository implements SupplierRepository {

    private final Map<UUID, Supplier> byId = new HashMap<>();
    private final Map<String, Supplier> byCode = new HashMap<>();

    public InMemorySupplierRepository putActive(String code, String name) {
        SupplierId id = SupplierId.of(UUID.randomUUID());
        Supplier s = new Supplier(id, code, name, Supplier.ACTIVE);
        byId.put(id.value(), s);
        byCode.put(code, s);
        return this;
    }

    @Override
    public Optional<Supplier> findById(SupplierId id) {
        return Optional.ofNullable(byId.get(id.value()));
    }

    @Override
    public Optional<Supplier> findByCode(String supplierCode) {
        return Optional.ofNullable(byCode.get(supplierCode));
    }

    @Override
    public List<Supplier> findAll() {
        List<Supplier> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparing(Supplier::supplierCode));
        return out;
    }

    @Override
    public Supplier defaultSupplier() {
        return findAll().stream()
            .filter(Supplier::isActive)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no active supplier seeded"));
    }
}
