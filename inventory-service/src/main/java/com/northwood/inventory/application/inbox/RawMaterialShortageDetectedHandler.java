package com.northwood.inventory.application.inbox;

import com.northwood.inventory.application.WarehouseLookup;
import com.northwood.inventory.application.replenishment.ReplenishmentDetectionService;
import com.northwood.inventory.domain.ReplenishmentRequest.Reason;
import com.northwood.manufacturing.domain.events.RawMaterialShortageDetected;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.35 Slice C — the manufacturing↔purchasing decoupling bridge.
 *
 * <p>Pre-§2.35, {@code manufacturing.RawMaterialShortageDetected} was consumed
 * directly by purchasing's {@code RawMaterialShortageDetectedHandler}, which
 * created a {@code PurchaseRequisition} with
 * {@code source_type='work_order_shortage'}. That was the only direct
 * operational edge between manufacturing and purchasing in the codebase.
 *
 * <p>§2.35 reroutes it through inventory: this handler converts each shortage
 * component into an {@link com.northwood.inventory.domain.ReplenishmentRequest}
 * with {@code reason='work_order_shortage'} via
 * {@link ReplenishmentDetectionService#raiseIfNoneOpen(UUID, UUID, java.math.BigDecimal, Reason)}.
 * The downstream routing (manufacturing or purchasing, by make-vs-buy) is
 * identical to the reorder-point path — one channel handles both triggers.
 * Slice D deletes the purchasing-side handler entirely; this handler is the
 * other side of that swap.
 *
 * <p>Raw materials are typically buy-only ({@code is_purchased=true},
 * {@code is_manufactured=false}), so the per-component
 * {@code raiseIfNoneOpen} call almost always routes to purchasing — but the
 * routing decision is left to the detection service (no special-case here)
 * so a vertically-integrated SKU stays self-consistent.
 *
 * <p>One ReplenishmentRequest per shortage component. The one-open-per-
 * (product, warehouse) partial unique index swallows the second-on-same-pair
 * case as a debug-logged no-op — see {@code ReplenishmentDetectionService}.
 */
@Component
public class RawMaterialShortageDetectedHandler extends AbstractInboxHandler<RawMaterialShortageDetected> {

    public static final String CONSUMER_NAME = "inventory.shortage-to-replenishment";

    private final ReplenishmentDetectionService detection;
    private final WarehouseLookup warehouses;

    public RawMaterialShortageDetectedHandler(
        InboxPort inbox,
        ReplenishmentDetectionService detection,
        WarehouseLookup warehouses,
        ObjectMapper json
    ) {
        super(inbox, json, RawMaterialShortageDetected.class, RawMaterialShortageDetected.EVENT_TYPE, CONSUMER_NAME);
        this.detection = detection;
        this.warehouses = warehouses;
    }

    @Override
    protected void apply(RawMaterialShortageDetected payload, EventEnvelope envelope) {
        UUID warehouseId = warehouses.findIdByCode(payload.warehouseCode());
        int raised = 0;
        for (RawMaterialShortageDetected.ShortageComponent c : payload.components()) {
            if (c.shortageQuantity() == null || c.shortageQuantity().signum() <= 0) {
                log.debug("[{}] skipping component product_id={} with non-positive shortage_quantity={}",
                    CONSUMER_NAME, c.componentProductId(), c.shortageQuantity());
                continue;
            }
            detection.raiseIfNoneOpen(
                c.componentProductId(),
                warehouseId,
                c.shortageQuantity(),
                Reason.WORK_ORDER_SHORTAGE
            );
            raised++;
        }
        log.info("[{}] processed {} ({}) for work_order={} warehouse={}: bridged {} of {} component(s) → ReplenishmentRequest(s)",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.workOrderId(), payload.warehouseCode(),
            raised, payload.components().size());
    }
}
