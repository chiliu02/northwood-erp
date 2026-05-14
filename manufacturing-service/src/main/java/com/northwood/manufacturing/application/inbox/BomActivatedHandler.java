package com.northwood.manufacturing.application.inbox;

import com.northwood.manufacturing.application.MaterialsCostRollupService;
import com.northwood.product.domain.events.BomActivated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.BomActivated}. Maintains the
 * {@code manufacturing.product_active_bom} projection and, in the same
 * transaction, kicks off the §2.8 Slice D BoM rollup so the activated
 * product's materialsCost is computed immediately. Co-exists with
 * manufacturing's existing {@code bom_header.is_active} column during the
 * migration period.
 */
@Component
public class BomActivatedHandler extends AbstractInboxHandler<BomActivated> {

    public static final String CONSUMER_NAME = "manufacturing.product-active-bom-projector";

    private final ProductActiveBomProjection projection;
    private final MaterialsCostRollupService rollup;

    public BomActivatedHandler(
        InboxPort inbox,
        ProductActiveBomProjection projection,
        MaterialsCostRollupService rollup,
        ObjectMapper json
    ) {
        super(inbox, json, BomActivated.class, BomActivated.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
        this.rollup = rollup;
    }

    @Override
    protected void apply(BomActivated payload, EventEnvelope envelope) {
        projection.apply(payload.aggregateId(), payload.newBomHeaderId());
        // §2.8 Slice D: a newly active BoM means materialsCost rolls up afresh.
        // The recompute also walks parents, so a multi-level activation cascade
        // (rare — typically one product activates at a time) reaches everything
        // in a single transaction.
        rollup.recomputeViaBom(payload.aggregateId(), "bom_activated");
    }
}
