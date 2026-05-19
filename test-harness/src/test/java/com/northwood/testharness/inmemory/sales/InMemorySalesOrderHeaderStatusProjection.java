package com.northwood.testharness.inmemory.sales;

import com.northwood.sales.application.inbox.SalesOrderHeaderStatusProjection;
import com.northwood.sales.domain.SalesOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemorySalesOrderHeaderStatusProjection implements SalesOrderHeaderStatusProjection {

    private final Map<UUID, SalesOrder.Status> statusByOrder = new HashMap<>();

    @Override
    public void markStatus(UUID salesOrderHeaderId, SalesOrder.Status headerStatus) {
        statusByOrder.put(salesOrderHeaderId, headerStatus);
    }

    public Optional<SalesOrder.Status> get(UUID salesOrderHeaderId) {
        return Optional.ofNullable(statusByOrder.get(salesOrderHeaderId));
    }
}
