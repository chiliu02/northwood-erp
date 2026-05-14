package com.northwood.sales.domain;

import java.util.Optional;

/**
 * Port for the SalesOrder aggregate. The infrastructure adapter is responsible
 * for writing the header + lines and draining {@link SalesOrder#pullPendingEvents()}
 * to the outbox in the same transaction opened by the application service.
 */
public interface SalesOrderRepository {

    Optional<SalesOrder> findById(SalesOrderId id);

    void save(SalesOrder order);
}
