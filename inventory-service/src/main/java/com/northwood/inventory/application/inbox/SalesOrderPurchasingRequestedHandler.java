package com.northwood.inventory.application.inbox;

import com.northwood.inventory.application.WarehouseLookup;
import com.northwood.inventory.application.replenishment.ReplenishmentDetectionService;
import com.northwood.sales.domain.events.SalesOrderPurchasingRequested;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.36: idempotent inbox handler for
 * {@code sales.SalesOrderPurchasingRequested}. The sales-side counterpart to
 * the §2.35 {@code RawMaterialShortageDetectedHandler} bridge — converts a
 * sales-driven shortage signal into one or more
 * {@link com.northwood.inventory.domain.ReplenishmentRequest} aggregates with
 * {@code reason = sales_order_shortage}.
 *
 * <p>One request per line. Crucially, sales-order-shortage requests are
 * excluded from the one-open-per-(product, warehouse) partial unique index
 * (§2.36 Slice A schema-prep) — multiple sales orders short on the same SKU
 * each get their own request, each back-referenced to a distinct
 * {@code source_sales_order_line_id} so the eventual
 * {@code ReplenishmentFulfilled} can un-park the originating fulfilment saga.
 *
 * <p>Skips lines whose SKU classification is unsourceable
 * ({@code is_purchased=false AND is_manufactured=false}) — those have no
 * routing target. The saga will eventually time out at
 * {@code purchasing_requested} until operator intervention (flip the SKU's
 * make-vs-buy on product master).
 *
 * <p>The routing decision (target_service = manufacturing | purchasing) is
 * delegated to {@link ReplenishmentDetectionService#raiseForSalesOrderShortage}
 * — inventory owns the routing per the §2.35 architectural rule. Manufacturing
 * already classified the line as purchased-only via the
 * {@code rejected_not_manufactured} outcome that triggered sales' reroute;
 * inventory's snapshot is the authoritative make-vs-buy source, so in the
 * normal case this lands on {@code TargetService.PURCHASING}, but a
 * vertically-integrated SKU could land on {@code MANUFACTURING} if inventory's
 * snapshot disagrees with manufacturing's projection — unlikely but
 * structurally permitted.
 */
@Component
public class SalesOrderPurchasingRequestedHandler
    extends AbstractInboxHandler<SalesOrderPurchasingRequested> {

    public static final String CONSUMER_NAME = "inventory.sales-order-purchasing-requested";

    private final ReplenishmentDetectionService detection;
    private final WarehouseLookup warehouses;

    public SalesOrderPurchasingRequestedHandler(
        InboxPort inbox,
        ReplenishmentDetectionService detection,
        WarehouseLookup warehouses,
        ObjectMapper json
    ) {
        super(inbox, json,
            SalesOrderPurchasingRequested.class,
            SalesOrderPurchasingRequested.EVENT_TYPE,
            CONSUMER_NAME);
        this.detection = detection;
        this.warehouses = warehouses;
    }

    @Override
    protected void apply(SalesOrderPurchasingRequested payload, EventEnvelope envelope) {
        UUID warehouseId = warehouses.findIdByCode(payload.warehouseCode());
        int raised = 0;
        for (SalesOrderPurchasingRequested.RequestedLine line : payload.lines()) {
            if (line.shortageQuantity() == null || line.shortageQuantity().signum() <= 0) {
                log.debug("[{}] skipping line {} product_id={} with non-positive shortage_quantity={}",
                    CONSUMER_NAME, line.lineNumber(), line.productId(), line.shortageQuantity());
                continue;
            }
            detection.raiseForSalesOrderShortage(
                line.productId(),
                warehouseId,
                line.shortageQuantity(),
                payload.salesOrderHeaderId(),
                line.salesOrderLineId()
            );
            raised++;
        }
        log.info("[{}] processed {} ({}) for sales_order={} warehouse={}: bridged {} of {} purchased-only line(s) → ReplenishmentRequest(s)",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.salesOrderHeaderId(), payload.warehouseCode(),
            raised, payload.lines().size());
    }
}
