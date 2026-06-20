package com.northwood.manufacturing.application;

import com.northwood.manufacturing.application.saga.WorkOrderSagaManager;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.WorkOrderRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Withdraw an order-pegged work order as the manufacturing leg of sales-order
 * compensation (driven by {@code inventory.OrderPeggedSupplyCancellationRequested}).
 * The mirror of {@code purchasing.PurchaseOrderService.compensateCancel}: loads the
 * WO, attempts {@link WorkOrder#cancel}, and on success saves it (emits
 * {@code WorkOrderCancelled} — inventory releases the reserved raw materials) +
 * terminates the WO-lifecycle saga ({@link WorkOrderSagaManager#cancel}). Returns a
 * {@link CompensationResult} so the inbox handler emits the
 * {@code WorkOrderCancellationApplied} ack addressed to the sales saga.
 */
@Service
public class WorkOrderCancellationService {

    /**
     * Outcome of a {@link #compensateCancel} attempt. {@code compensated} is the
     * success flag (WO withdrawn or already cancelled); {@code previousStatus} is
     * the WO status before the attempt (null if the WO was not found).
     */
    public record CompensationResult(boolean compensated, String previousStatus, String detail) {}

    private static final Logger log = LoggerFactory.getLogger(WorkOrderCancellationService.class);

    private final WorkOrderRepository workOrders;
    private final WorkOrderSagaManager sagaManager;

    public WorkOrderCancellationService(WorkOrderRepository workOrders, WorkOrderSagaManager sagaManager) {
        this.workOrders = workOrders;
        this.sagaManager = sagaManager;
    }

    /**
     * Withdraw the work order for compensation.
     * <ul>
     *   <li>WO {@code released} / already-{@code cancelled} → withdrawn (or no-op),
     *       {@code compensated = true};</li>
     *   <li>WO in progress / completed / closed → un-compensatable leaf,
     *       {@code compensated = false} (the saga escalates to
     *       {@code compensation_failed});</li>
     *   <li>WO not found → {@code compensated = false} (treat as a failed leg so the
     *       saga surfaces it rather than silently completing).</li>
     * </ul>
     */
    @Transactional
    public CompensationResult compensateCancel(UUID workOrderId, String reason) {
        WorkOrder workOrder = workOrders.findById(WorkOrderId.of(workOrderId)).orElse(null);
        if (workOrder == null) {
            log.warn("compensate-cancel: no work_order {} to withdraw ({}); reporting failed leg",
                workOrderId, reason);
            return new CompensationResult(false, null, "work order not found");
        }
        String previous = workOrder.status().code();
        try {
            workOrder.cancel(reason);
        } catch (WorkOrder.WoNotCompensatableException e) {
            log.warn("compensate-cancel: work_order {} is un-compensatable (status={}): {}",
                workOrderId, previous, e.getMessage());
            return new CompensationResult(false, previous, e.getMessage());
        }
        workOrders.save(workOrder);
        sagaManager.cancel(workOrderId);
        log.info("compensate-cancel: withdrew work_order {} (id={}, was {}) reason={}",
            workOrder.workOrderNumber(), workOrderId, previous, reason);
        return new CompensationResult(true, previous, reason);
    }
}
