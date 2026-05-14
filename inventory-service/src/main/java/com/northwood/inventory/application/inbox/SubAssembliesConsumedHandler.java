package com.northwood.inventory.application.inbox;

import com.northwood.inventory.application.WipBalanceWriter;
import com.northwood.manufacturing.domain.events.SubAssembliesConsumed;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §3.3: handler for {@code manufacturing.SubAssembliesConsumed}. Decrements
 * {@code wip_balance.on_hand_quantity} per sub-assembly product — pairing
 * with the WIP bumps that fire on each child WO's completion.
 *
 * <p>Idempotent via the inbox dedupe. The decrement is bounded to
 * {@code on_hand_quantity}'s non-negative CHECK on the table; if a redelivery
 * somehow gets past dedupe and underflows, Postgres rejects with a constraint
 * violation rather than silently going negative.
 *
 * <p><b>Trusts emitter's quantities verbatim.</b> The emitter
 * {@code WorkOrderOperationService.emitSubAssembliesConsumedIfParent} drops
 * children whose {@code completed_quantity} is null (a data-corruption signal
 * for a row in {@code status='completed'}) and logs at WARN there. A dropped
 * child means WIP for that child product is not decremented here; it stays
 * elevated until a future reconciliation. See the emitter's Javadoc for the
 * full silent-fallback rationale.
 */
@Component
public class SubAssembliesConsumedHandler extends AbstractInboxHandler<SubAssembliesConsumed> {

    public static final String CONSUMER_NAME = "inventory.wip-consume";

    private final WipBalanceWriter wipBalances;

    public SubAssembliesConsumedHandler(InboxPort inbox, WipBalanceWriter wipBalances, ObjectMapper json) {
        super(inbox, json, SubAssembliesConsumed.class, SubAssembliesConsumed.EVENT_TYPE, CONSUMER_NAME);
        this.wipBalances = wipBalances;
    }

    @Override
    protected void apply(SubAssembliesConsumed payload, EventEnvelope envelope) {
        if (payload.items() == null || payload.items().isEmpty()) {
            log.debug("[{}] {} carried no items — nothing to consume", CONSUMER_NAME, envelope.eventId());
        } else {
            for (SubAssembliesConsumed.ConsumedItem item : payload.items()) {
                wipBalances.decrement(item.productId(), item.quantity());
            }
        }

        log.info("[{}] consumed {} sub-assembly item(s) for parent work_order={}",
            CONSUMER_NAME,
            payload.items() == null ? 0 : payload.items().size(),
            payload.aggregateId());
    }
}
