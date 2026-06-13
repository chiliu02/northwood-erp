package com.northwood.inventory.application.inbox;

import com.northwood.inventory.application.StockBalanceWriter;
import com.northwood.inventory.application.StockMovementWriter;
import com.northwood.inventory.application.WarehouseLookup;
import com.northwood.inventory.application.WipBalanceWriter;
import com.northwood.inventory.domain.StockMovementDirection;
import com.northwood.inventory.domain.StockMovementSourceTypes;
import com.northwood.inventory.domain.StockMovementType;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.ReplenishmentRequestId;
import com.northwood.inventory.domain.ReplenishmentRequestRepository;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code manufacturing.WorkOrderManufacturingCompleted}.
 * "Production confirmation" — bumps {@code stock_balance.on_hand_quantity}
 * for the finished good so subsequent shipments can decrement against real
 * stock instead of going negative.
 *
 * <p>Sub-assembly children (non-null {@code parentWorkOrderId}) DO bump a
 * separate {@code inventory.wip_balance} row instead of
 * {@code stock_balance} — the produced sub-assembly sits in WIP until the
 * parent consumes it. The consume path is driven by
 * {@link SubAssembliesConsumedHandler} when the parent emits
 * {@code SubAssembliesConsumed}.
 *
 * <p>Default warehouse is {@code MAIN} since the schema doesn't currently
 * model "where was this WO physically built" — manufacturing seeded a single
 * work centre under that warehouse.
 *
 * <p>If this WO was the dispatch target of a {@link ReplenishmentRequest}, also
 * flip the request to {@code fulfilled} (which emits
 * {@code inventory.ReplenishmentFulfilled} via the outbox). Sub-assembly
 * children aren't checked — replenishment WOs don't recurse sub-assemblies
 * today (see {@code WorkOrderReleaseService.releaseForReplenishment}'s Javadoc
 * on the sub-assembly skip).
 */
@Component
public class WorkOrderManufacturingCompletedHandler extends AbstractInboxHandler<WorkOrderManufacturingCompleted> {

    public static final String HANDLER_NAME = "inventory.production-confirmation";

    private final StockBalanceWriter stockBalances;
    private final WipBalanceWriter wipBalances;
    private final WarehouseLookup warehouses;
    private final StockMovementWriter movements;
    private final ReplenishmentRequestRepository replenishmentRequests;

    public WorkOrderManufacturingCompletedHandler(
        InboxPort inbox,
        ObjectMapper json,
        StockBalanceWriter stockBalances,
        WipBalanceWriter wipBalances,
        WarehouseLookup warehouses,
        StockMovementWriter movements,
        ReplenishmentRequestRepository replenishmentRequests
    ) {
        super(inbox, json, WorkOrderManufacturingCompleted.class, WorkOrderManufacturingCompleted.EVENT_TYPE, HANDLER_NAME);
        this.stockBalances = stockBalances;
        this.wipBalances = wipBalances;
        this.warehouses = warehouses;
        this.movements = movements;
        this.replenishmentRequests = replenishmentRequests;
    }

    @Override
    protected void apply(WorkOrderManufacturingCompleted payload, EventEnvelope envelope) {
        UUID warehouseId = warehouses.findIdByCode(WarehouseCodes.MAIN);
        if (payload.parentWorkOrderId() != null) {
            // Sub-assembly child completion → bump WIP, not FG stock. WIP
            // movements are out of scope for stock_movement (which is
            // shippable-stock audit); a future slice could add a parallel
            // wip_movement table.
            wipBalances.bump(warehouseId, payload.finishedProductId(), payload.completedQuantity());
            log.info("[{}] bumped WIP balance for sub-assembly {} ({}) by {} (work_order={}, parent={})",
                HANDLER_NAME, payload.finishedProductSku(), payload.finishedProductId(),
                payload.completedQuantity(), payload.aggregateId(), payload.parentWorkOrderId());
        } else {
            // Top-level FG completion → bump shippable stock + audit row.
            stockBalances.bump(warehouseId, payload.finishedProductId(), payload.completedQuantity());
            movements.record(
                warehouseId, payload.finishedProductId(),
                payload.finishedProductSku(), payload.finishedProductSku(),  // name approximated by sku — event doesn't carry name
                StockMovementType.FINISHED_GOODS_RECEIPT, StockMovementDirection.IN,
                payload.completedQuantity(), null,
                StockMovementSourceTypes.WORK_ORDER, payload.aggregateId(), null
            );
            log.info("[{}] bumped FG stock_balance for {} ({}) by {} (work_order={})",
                HANDLER_NAME, payload.finishedProductSku(), payload.finishedProductId(),
                payload.completedQuantity(), payload.aggregateId());

            // If this WO fulfils an inventory replenishment, dispatch-and-fulfil
            // it here. A completed replenishment WO is proof its dispatch
            // happened, so markDispatched (idempotent) runs before markFulfilled
            // rather than waiting for ReplenishmentDispatched(WORK_ORDER) to have
            // been processed first — closing the completion-vs-dispatch race
            // order-independently (the WO twin of the PR→PO link fix).
            if (payload.replenishmentRequestId() != null) {
                Optional<ReplenishmentRequest> r = replenishmentRequests.findById(
                    ReplenishmentRequestId.of(payload.replenishmentRequestId()));
                if (r.isPresent()
                    && r.get().status() != ReplenishmentRequest.Status.FULFILLED
                    && r.get().status() != ReplenishmentRequest.Status.CANCELLED) {
                    ReplenishmentRequest req = r.get();
                    // requested -> dispatched, or idempotent no-op if ReplenishmentDispatched already won the race.
                    req.markDispatched(ReplenishmentRequest.DispatchedAggregateKind.WORK_ORDER, payload.aggregateId());
                    // Atomic peg: an order-pegged replenishment's output is dedicated
                    // to the originating SO line. Reserve it in THIS transaction (right
                    // after the on_hand credit) so it never enters free ATP and can't be
                    // stolen by a concurrent order. The generated available_quantity
                    // (on_hand - reserved) stays flat, so reporting's ATP view excludes
                    // it automatically. Releasing this peg on cancel is the un-peg job.
                    if (req.reason() == ReplenishmentRequest.Reason.ORDER_PEGGED) {
                        boolean pegged = stockBalances.tryReserveOnHand(
                            warehouseId, payload.finishedProductId(), payload.completedQuantity());
                        if (pegged) {
                            log.info("[{}] pegged {} of {} ({}) to sales_order={} sales_order_line={} (atomic credit+reserve)",
                                HANDLER_NAME, payload.completedQuantity(), payload.finishedProductSku(),
                                payload.finishedProductId(), req.sourceSalesOrderHeaderId(), req.sourceSalesOrderLineId());
                        } else {
                            log.warn("[{}] could not peg-reserve {} of {} for sales_order={} — free stock insufficient "
                                + "immediately after credit (concurrent reservation?); SO line falls back to the retry path",
                                HANDLER_NAME, payload.completedQuantity(), payload.finishedProductId(),
                                req.sourceSalesOrderHeaderId());
                        }
                    }
                    req.markFulfilled();
                    replenishmentRequests.save(req);
                    log.info("[{}] fulfilled replenishment_request={} via work_order={}",
                        HANDLER_NAME, req.id().value(), payload.aggregateId());
                }
            }
        }
    }
}
