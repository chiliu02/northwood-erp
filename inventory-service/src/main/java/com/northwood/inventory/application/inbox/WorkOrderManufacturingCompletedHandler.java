package com.northwood.inventory.application.inbox;

import com.northwood.inventory.application.StockBalanceWriter;
import com.northwood.inventory.application.StockMovementWriter;
import com.northwood.inventory.application.WarehouseLookup;
import com.northwood.inventory.application.WipBalanceWriter;
import com.northwood.inventory.domain.StockMovementDirection;
import com.northwood.inventory.domain.StockMovementSourceTypes;
import com.northwood.inventory.domain.StockMovementType;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
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
 */
@Component
public class WorkOrderManufacturingCompletedHandler extends AbstractInboxHandler<WorkOrderManufacturingCompleted> {

    public static final String CONSUMER_NAME = "inventory.production-confirmation";
    private static final String DEFAULT_WAREHOUSE = "MAIN";

    private final StockBalanceWriter stockBalances;
    private final WipBalanceWriter wipBalances;
    private final WarehouseLookup warehouses;
    private final StockMovementWriter movements;

    public WorkOrderManufacturingCompletedHandler(
        InboxPort inbox,
        ObjectMapper json,
        StockBalanceWriter stockBalances,
        WipBalanceWriter wipBalances,
        WarehouseLookup warehouses,
        StockMovementWriter movements
    ) {
        super(inbox, json, WorkOrderManufacturingCompleted.class, WorkOrderManufacturingCompleted.EVENT_TYPE, CONSUMER_NAME);
        this.stockBalances = stockBalances;
        this.wipBalances = wipBalances;
        this.warehouses = warehouses;
        this.movements = movements;
    }

    @Override
    protected void apply(WorkOrderManufacturingCompleted payload, EventEnvelope envelope) {
        UUID warehouseId = warehouses.findIdByCode(DEFAULT_WAREHOUSE);
        if (payload.parentWorkOrderId() != null) {
            // Sub-assembly child completion → bump WIP, not FG stock. WIP
            // movements are out of scope for stock_movement (which is
            // shippable-stock audit); a future slice could add a parallel
            // wip_movement table.
            wipBalances.bump(warehouseId, payload.finishedProductId(), payload.completedQuantity());
            log.info("[{}] bumped WIP balance for sub-assembly {} ({}) by {} (work_order={}, parent={})",
                CONSUMER_NAME, payload.finishedProductSku(), payload.finishedProductId(),
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
                CONSUMER_NAME, payload.finishedProductSku(), payload.finishedProductId(),
                payload.completedQuantity(), payload.aggregateId());
        }
    }
}
