package com.northwood.sales.application.saga;

import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.application.saga.SagaPort;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for the sales-order fulfilment saga. Extends the shared
 * {@link SagaPort} contract with a lookup by {@code sales_order_id} so
 * inbox handlers receiving inventory/manufacturing/finance events can find the
 * row they need to advance.
 */
public interface SalesOrderFulfilmentSagaPort extends SagaPort<SalesOrderFulfilmentSaga> {

    Optional<SalesOrderFulfilmentSaga> findBySalesOrderId(UUID salesOrderId);
}
