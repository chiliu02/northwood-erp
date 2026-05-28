package com.northwood.inventory.application.inbox;

import com.northwood.product.domain.events.ProductCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ProductCreated}. Inserts a
 * stub {@code inventory.stock_item} row so subsequent product-master events
 * for the SKU (notably {@code ReorderPolicyChanged}) have a row to project
 * onto. §1F.2: closes the gap previously documented inline on
 * {@link StockItemProjection#applyReorderPolicy}.
 *
 * <p>§2.35 Slice A extension: also seeds a default row in
 * {@code inventory.product_replenishment} via
 * {@link ProductReplenishmentProjection#seedDefaultsFromProductType} so the
 * §2.35 detection service has non-empty make-vs-buy flags for day-zero SKUs
 * before any {@code MakeVsBuyChanged} event arrives.
 */
@Component
public class ProductCreatedHandler extends AbstractInboxHandler<ProductCreated> {

    public static final String CONSUMER_NAME = "inventory.product-created";

    private final ProductCreatedProjection stockItem;
    private final ProductReplenishmentProjection replenishment;

    public ProductCreatedHandler(
        InboxPort inbox,
        ProductCreatedProjection stockItem,
        ProductReplenishmentProjection replenishment,
        ObjectMapper json
    ) {
        super(inbox, json, ProductCreated.class, ProductCreated.EVENT_TYPE, CONSUMER_NAME);
        this.stockItem = stockItem;
        this.replenishment = replenishment;
    }

    @Override
    protected void apply(ProductCreated payload, EventEnvelope envelope) {
        stockItem.apply(
            payload.aggregateId(),
            payload.sku(),
            payload.name(),
            payload.productType()
        );
        replenishment.seedDefaultsFromProductType(
            payload.aggregateId(),
            payload.productType()
        );

        log.info("[{}] applied {} ({}) for product_id={} sku={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.sku());
    }
}
