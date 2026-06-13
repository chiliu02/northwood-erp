package com.northwood.manufacturing.application.inbox;

import com.northwood.manufacturing.application.MaterialsCostRollupService;
import com.northwood.product.domain.events.ActiveBomChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ActiveBomChanged}. Maintains
 * the {@code manufacturing.product_card.active_bom_header_id} column and, in the same
 * transaction, kicks off the BoM rollup so the activated product's
 * materialsCost is computed immediately. Co-exists with manufacturing's
 * existing {@code bom_header.is_active} column during the migration period.
 *
 * <p>Renamed from {@code BomActivatedHandler} 2026-05-14 to track
 * the producer-side event rename; {@code HANDLER_NAME} is unchanged
 * ({@code manufacturing.product-active-bom-projector}) — it was named
 * after the read-model column it maintains, not after the event, so the
 * inbox dedupe key survives the rename without coordination.
 */
@Component
public class ActiveBomChangedHandler extends AbstractInboxHandler<ActiveBomChanged> {

    public static final String HANDLER_NAME = "manufacturing.product-active-bom-projector";

    private final ProductActiveBomProjection projection;
    private final MaterialsCostRollupService rollup;

    public ActiveBomChangedHandler(
        InboxPort inbox,
        ProductActiveBomProjection projection,
        MaterialsCostRollupService rollup,
        ObjectMapper json
    ) {
        super(inbox, json, ActiveBomChanged.class, ActiveBomChanged.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
        this.rollup = rollup;
    }

    @Override
    protected void apply(ActiveBomChanged payload, EventEnvelope envelope) {
        projection.apply(payload.aggregateId(), payload.newBomHeaderId());
        // A newly active BoM means materialsCost rolls up afresh. The recompute
        // also walks parents, so a multi-level activation cascade (rare —
        // typically one product activates at a time) reaches everything in a
        // single transaction.
        rollup.recomputeViaBom(payload.aggregateId(), "bom_activated");
    }
}
