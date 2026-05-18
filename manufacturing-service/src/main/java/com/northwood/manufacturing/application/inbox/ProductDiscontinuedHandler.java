package com.northwood.manufacturing.application.inbox;

import com.northwood.manufacturing.application.BomLookup;
import com.northwood.product.domain.events.ProductDiscontinued;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ProductDiscontinued}. Retires
 * the product from manufacturing by writing to two facets of
 * {@code manufacturing.product_card}:
 * <ul>
 *   <li>Replenishment facets — flips {@code is_purchased} / {@code is_manufactured}
 *       to {@code false} and stamps {@code discontinued_at}, so
 *       {@code ManufacturingRequestedHandler}'s {@code !isManufactured()}
 *       guard rejects any new sales-line for the SKU, the cost-rollup engine
 *       treats it as no longer sourceable, and {@link BomEditService#addLine}
 *       rejects new BOM lines referencing it.</li>
 *   <li>{@code active_bom_header_id = null} — equivalent in effect to an
 *       {@code ActiveBomChanged} with a null newBomHeaderId, so any consumer
 *       reading the active BOM gets the empty signal too.</li>
 *   <li><b>§1.4 B.2 parent-BOM cascade:</b> queries
 *       {@link BomLookup#findParentProductIdsByComponent(UUID)} for finished
 *       products whose active BOM lists the discontinued product as a
 *       component, and clears each parent's
 *       {@code product_card.active_bom_header_id} too. New orders
 *       for the parent FG then land at {@code rejected_no_bom} in
 *       {@code ManufacturingRequestedHandler}, surfacing the gap synchronously
 *       on the dispatch step instead of leaking through to a make-to-order
 *       saga that tries to reserve a discontinued raw material. A planner
 *       can restore the parent by activating a new BOM revision that
 *       doesn't reference the discontinued SKU. WARN-logged per affected
 *       parent so the impact is visible.</li>
 * </ul>
 */
@Component
public class ProductDiscontinuedHandler extends AbstractInboxHandler<ProductDiscontinued> {

    public static final String CONSUMER_NAME = "manufacturing.product-discontinued";

    private final ProductReplenishmentProjection replenishment;
    private final ProductActiveBomProjection activeBom;
    private final BomLookup boms;

    public ProductDiscontinuedHandler(
        InboxPort inbox,
        ProductReplenishmentProjection replenishment,
        ProductActiveBomProjection activeBom,
        BomLookup boms,
        ObjectMapper json
    ) {
        super(inbox, json, ProductDiscontinued.class, ProductDiscontinued.EVENT_TYPE, CONSUMER_NAME);
        this.replenishment = replenishment;
        this.activeBom = activeBom;
        this.boms = boms;
    }

    @Override
    protected void apply(ProductDiscontinued payload, EventEnvelope envelope) {
        UUID productId = payload.aggregateId();
        replenishment.applyDiscontinued(productId);
        activeBom.apply(productId, null);

        List<UUID> affectedParents = boms.findParentProductIdsByComponent(productId);
        for (UUID parentId : affectedParents) {
            activeBom.apply(parentId, null);
            log.warn(
                "[{}] product_id={} discontinued; cascade-cleared active BOM on parent product_id={}. "
                    + "Parent FG will now reject new orders with rejected_no_bom until a new BOM revision is activated.",
                CONSUMER_NAME, productId, parentId
            );
        }

        log.info("[{}] applied {} ({}) for product_id={} (replenishment flags off + active BOM cleared + {} parent BOM(s) cascade-cleared)",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(), productId, affectedParents.size());
    }
}
