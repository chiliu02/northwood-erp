package com.northwood.testharness.inmemory.manufacturing;

import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.WorkOrderMaterial;
import com.northwood.manufacturing.domain.saga.MakeToOrderSaga;
import com.northwood.manufacturing.application.saga.MakeToOrderShortageRecoveryQueryPort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory {@link MakeToOrderShortageRecoveryQueryPort}. Joins
 * {@link InMemoryMakeToOrderSagaPort} (filtering on {@code raw_material_shortage})
 * to {@link InMemoryWorkOrderRepository} (filtering on the saga's WO's
 * materials). Returns saga ids whose WO has at least one material whose
 * component product is in the received-product set.
 */
public final class InMemoryMakeToOrderShortageRecoveryQueryPort implements MakeToOrderShortageRecoveryQueryPort {

    private final InMemoryMakeToOrderSagaPort sagas;
    private final InMemoryWorkOrderRepository workOrders;

    public InMemoryMakeToOrderShortageRecoveryQueryPort(
        InMemoryMakeToOrderSagaPort sagas,
        InMemoryWorkOrderRepository workOrders
    ) {
        this.sagas = sagas;
        this.workOrders = workOrders;
    }

    @Override
    public List<UUID> findShortageSagaIdsForReceivedProducts(Collection<UUID> productIds) {
        Set<UUID> received = new HashSet<>(productIds);
        List<UUID> out = new ArrayList<>();
        for (MakeToOrderSaga saga : sagas.all()) {
            if (!MakeToOrderSaga.RAW_MATERIAL_SHORTAGE.equals(saga.state())) continue;
            UUID workOrderId = saga.workOrderId();
            if (workOrderId == null) continue;
            WorkOrder workOrder = workOrders.findById(WorkOrderId.of(workOrderId)).orElse(null);
            if (workOrder == null) continue;
            for (WorkOrderMaterial m : workOrder.materials()) {
                if (received.contains(m.componentProductId())) {
                    out.add(saga.sagaId());
                    break;
                }
            }
        }
        return out;
    }
}
