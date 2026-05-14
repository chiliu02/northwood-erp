package com.northwood.manufacturing.application.inbox;

import com.northwood.product.domain.events.ProductDiscontinued;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ProductDiscontinued}. Retires
 * the product from manufacturing by writing two read-side rows:
 * <ul>
 *   <li>{@code manufacturing.product_replenishment} — both flags flipped to
 *       {@code false}, so {@code ManufacturingRequestedHandler}'s
 *       {@code !isManufactured()} guard rejects any new sales-line for the
 *       SKU, and the cost-rollup engine treats it as no longer sourceable.</li>
 *   <li>{@code manufacturing.product_active_bom.active_bom_header_id = null} —
 *       equivalent in effect to an {@code ActiveBomChanged} with a null newBomHeaderId
 *       (see {@code ActiveBomChanged} Javadoc), so any consumer reading the active
 *       BOM gets the empty signal too.</li>
 * </ul>
 */
@Component
public class ProductDiscontinuedHandler extends AbstractInboxHandler<ProductDiscontinued> {

    public static final String CONSUMER_NAME = "manufacturing.product-discontinued";

    private final ProductReplenishmentProjection replenishment;
    private final ProductActiveBomProjection activeBom;

    public ProductDiscontinuedHandler(
        InboxPort inbox,
        ProductReplenishmentProjection replenishment,
        ProductActiveBomProjection activeBom,
        ObjectMapper json
    ) {
        super(inbox, json, ProductDiscontinued.class, ProductDiscontinued.EVENT_TYPE, CONSUMER_NAME);
        this.replenishment = replenishment;
        this.activeBom = activeBom;
    }

    @Override
    protected void apply(ProductDiscontinued payload, EventEnvelope envelope) {
        replenishment.applyDiscontinued(payload.aggregateId());
        activeBom.apply(payload.aggregateId(), null);

        log.info("[{}] applied {} ({}) for product_id={} (replenishment flags off + active BOM cleared)",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(), payload.aggregateId());
    }
}
