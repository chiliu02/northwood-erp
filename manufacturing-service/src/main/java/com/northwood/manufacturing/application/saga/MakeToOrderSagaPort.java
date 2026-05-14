package com.northwood.manufacturing.application.saga;

import com.northwood.manufacturing.domain.saga.MakeToOrderSaga;
import com.northwood.shared.application.saga.SagaPort;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-service port for the {@code manufacturing.make_to_order_saga} table.
 * Adds {@link #findByWorkOrderId} so application code can advance the saga
 * after operations on a specific work order complete.
 */
public interface MakeToOrderSagaPort extends SagaPort<MakeToOrderSaga> {

    Optional<MakeToOrderSaga> findByWorkOrderId(UUID workOrderId);
}
