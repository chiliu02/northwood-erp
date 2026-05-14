package com.northwood.purchasing.application.saga;

import com.northwood.purchasing.domain.saga.PurchaseToPaySaga;
import com.northwood.shared.application.saga.SagaPort;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-service port for the {@code purchasing.purchase_to_pay_saga} table.
 * Adds {@link #findByPurchaseOrderId} so application code can advance the
 * saga after goods receipt / invoice / payment events arrive.
 */
public interface PurchaseToPaySagaPort extends SagaPort<PurchaseToPaySaga> {

    Optional<PurchaseToPaySaga> findByPurchaseOrderId(UUID purchaseOrderHeaderId);
}
