package com.northwood.manufacturing.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.WorkOrderRepository;
import com.northwood.manufacturing.application.saga.MakeToOrderSagaManager;
import com.northwood.manufacturing.domain.events.SalesOrderCancellationApplied;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels every active work order tied to a sales order and emits
 * {@code manufacturing.SalesOrderCancellationApplied} so the sales fulfilment
 * saga can advance. Active = status NOT IN
 * ({@code completed}, {@code closed}, {@code cancelled}).
 *
 * <p>Each WO cancel: aggregate's {@link WorkOrder#cancel} flips status to
 * {@code 'cancelled'}, the repository persists + drains
 * {@link WorkOrder#pullPendingEvents WorkOrderCancelled} to the outbox.
 * Inventory's {@code WorkOrderCancelledHandler} consumes that event to
 * release the raw-material reservation tied to this WO.
 *
 * <p>Each cancelled WO's make-to-order saga is force-flipped to
 * {@code 'compensated'} in the same transaction.
 */
@Service
public class WorkOrderCancellationService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderCancellationService.class);

    private final WorkOrderRepository workOrders;
    private final MakeToOrderSagaManager sagaManager;
    private final OutboxPort outbox;
    private final ObjectMapper json;

    public WorkOrderCancellationService(
        WorkOrderRepository workOrders,
        MakeToOrderSagaManager sagaManager,
        OutboxPort outbox,
        ObjectMapper json
    ) {
        this.workOrders = workOrders;
        this.sagaManager = sagaManager;
        this.outbox = outbox;
        this.json = json;
    }

    /**
     * Cancel every active WO for a sales order in response to
     * {@code sales.SalesOrderCancellationRequested}. The list of WO ids is
     * loaded by {@code findActiveIdsForSalesOrder}; each id is then re-loaded
     * via {@code findById} for the actual mutation.
     *
     * <p><b>Silent-fallback contract.</b> Between {@code findActiveIdsForSalesOrder}
     * and {@code findById}, a concurrent transaction could (in theory) delete
     * the WO row — there is no FK or other guarantee that the id remains
     * resolvable. Today no code path deletes work orders, so the gap is
     * dead-defensive. If the {@code findById} miss does fire, the WO is
     * silently skipped, the {@code cancelled} count returned in
     * {@code SalesOrderCancellationApplied} will be short by N, and the sales
     * fulfilment saga will see fewer cancellations acknowledged than it
     * requested. A WARN log fires naming the missing WO so the divergence is
     * visible. Throwing is rejected because (1) there's nothing the saga can
     * do with a hard failure mid-loop — partial cancellation is already a
     * coherent outcome, (2) the sales-side ack doesn't depend on the count
     * being exact (it just records "manufacturing acked").
     */
    @Transactional
    public void cancelForSalesOrder(UUID salesOrderHeaderId, String reason) {
        List<UUID> activeWoIds = workOrders.findActiveIdsForSalesOrder(salesOrderHeaderId);

        int cancelled = 0;
        for (UUID woId : activeWoIds) {
            WorkOrder wo = workOrders.findById(WorkOrderId.of(woId)).orElse(null);
            if (wo == null) {
                log.warn(
                    "cancelForSalesOrder sales_order={} work_order={} disappeared between findActiveIdsForSalesOrder and findById; "
                        + "skipping. cancelled count on the ack will be short by 1.",
                    salesOrderHeaderId, woId
                );
                continue;
            }
            wo.cancel(reason);
            workOrders.save(wo);
            cancelled++;

            sagaManager.cancelForWorkOrder(woId);
        }

        SalesOrderCancellationApplied ack = new SalesOrderCancellationApplied(
            UUID.randomUUID(), salesOrderHeaderId, cancelled, Instant.now()
        );
        try {
            outbox.appendPending(OutboxRow.pending(
                ack.eventId(),
                WorkOrder.AGGREGATE_TYPE,
                ack.aggregateId(),
                ack.eventType(),
                ack.eventVersion(),
                json.writeValueAsString(ack),
                null, null, null,
                null  // actor: saga-driven; propagation from inbound envelope is a B2 follow-up
            ));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialise " + SalesOrderCancellationApplied.EVENT_TYPE, e);
        }

        log.info("cancelled {} work order(s) for sales_order={} (reason={})",
            cancelled, salesOrderHeaderId, reason);
    }
}
