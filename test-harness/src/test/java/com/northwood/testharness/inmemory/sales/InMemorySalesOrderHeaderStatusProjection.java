package com.northwood.testharness.inmemory.sales;

import com.northwood.sales.application.inbox.SalesOrderHeaderStatusProjection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemorySalesOrderHeaderStatusProjection implements SalesOrderHeaderStatusProjection {

    private final Map<UUID, String> statusByOrder = new HashMap<>();

    @Override
    public void markStatus(UUID salesOrderHeaderId, String headerStatus) {
        statusByOrder.put(salesOrderHeaderId, headerStatus);
    }

    public Optional<String> get(UUID salesOrderHeaderId) {
        return Optional.ofNullable(statusByOrder.get(salesOrderHeaderId));
    }
}
