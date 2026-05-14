package com.northwood.manufacturing.application.inbox;

import com.northwood.manufacturing.application.MaterialsCostRollupService;
import com.northwood.purchasing.domain.events.SupplierProductPriceChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.8 Slice C: drives the materialsCost rollup engine from
 * {@code purchasing.SupplierProductPriceChanged}. Routing logic lives in
 * {@link MaterialsCostRollupService}; this handler is the
 * inbox-dedupe-deserialise glue.
 */
@Component
public class SupplierProductPriceChangedHandler extends AbstractInboxHandler<SupplierProductPriceChanged> {

    public static final String CONSUMER_NAME = "manufacturing.materials-cost-rollup";

    private final MaterialsCostRollupService rollup;

    public SupplierProductPriceChangedHandler(
        InboxPort inbox,
        MaterialsCostRollupService rollup,
        ObjectMapper json
    ) {
        super(inbox, json, SupplierProductPriceChanged.class, SupplierProductPriceChanged.EVENT_TYPE, CONSUMER_NAME);
        this.rollup = rollup;
    }

    @Override
    protected void apply(SupplierProductPriceChanged payload, EventEnvelope envelope) {
        rollup.onSupplierPriceChange(
            payload.supplierId(),
            payload.productId(),
            payload.currencyCode(),
            payload.newUnitPrice()
        );
    }
}
