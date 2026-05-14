package com.northwood.testharness.inmemory.inventory;

import com.northwood.inventory.application.WarehouseLookup;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class InMemoryWarehouseLookup implements WarehouseLookup {

    private final Map<String, UUID> byCode = new HashMap<>();

    public InMemoryWarehouseLookup put(String warehouseCode, UUID warehouseId) {
        byCode.put(warehouseCode, warehouseId);
        return this;
    }

    @Override
    public UUID findIdByCode(String warehouseCode) {
        UUID id = byCode.get(warehouseCode);
        if (id == null) {
            throw new IllegalStateException("No warehouse for code=" + warehouseCode);
        }
        return id;
    }
}
