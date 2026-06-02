package com.northwood.finance.application.inbox;

import com.northwood.finance.application.JournalEntryService;
import com.northwood.finance.application.JournalEntryService.LineCost;
import com.northwood.finance.application.ProductCardLookup;
import com.northwood.manufacturing.domain.events.SubAssembliesConsumed;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Perpetual WIP, multi-level. Inbox handler for
 * {@code manufacturing.SubAssembliesConsumed}: when a parent work order consumes
 * its completed sub-assembly children, posts Dr 1230 WIP / Cr 1220 Finished
 * Goods for the sum of {@code consumedQuantity * standardCost}. Each child's
 * completion took its value into 1220 ({@link WorkOrderManufacturingCompletedWipHandler});
 * this rolls that value back out of finished goods and into the parent's WIP, so
 * the parent's own completion releases the full rolled-up standard cost and WIP
 * nets to zero up the BOM.
 *
 * <p>Emitted exactly once per parent completion — idempotency is the inbox dedup
 * on {@code eventId} (no extra gate; the roll-in simply adds to the parent's
 * running WIP value).
 */
@Component
public class SubAssembliesConsumedWipHandler extends AbstractInboxHandler<SubAssembliesConsumed> {

    public static final String CONSUMER_NAME = "finance.wip.sub-assemblies-consumed";

    private final JournalEntryService journals;
    private final ProductCardLookup productCards;
    private final WorkOrderWipProjection workOrderWip;

    public SubAssembliesConsumedWipHandler(
        InboxPort inbox,
        JournalEntryService journals,
        ProductCardLookup productCards,
        WorkOrderWipProjection workOrderWip,
        ObjectMapper json
    ) {
        super(inbox, json, SubAssembliesConsumed.class, SubAssembliesConsumed.EVENT_TYPE, CONSUMER_NAME);
        this.journals = journals;
        this.productCards = productCards;
        this.workOrderWip = workOrderWip;
    }

    @Override
    protected void apply(SubAssembliesConsumed payload, EventEnvelope envelope) {
        List<LineCost> lineCosts = new ArrayList<>();
        if (payload.items() != null) {
            for (var item : payload.items()) {
                BigDecimal qty = item.quantity() == null ? BigDecimal.ZERO : item.quantity();
                BigDecimal stdCost = productCards.findStandardCost(item.productId()).orElse(BigDecimal.ZERO);
                lineCosts.add(new LineCost(item.productId(), qty.multiply(stdCost)));
            }
        }
        BigDecimal total = lineCosts.stream().map(LineCost::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            log.debug("[{}] parent work_order={} consumed zero standard-cost sub-assemblies — skipping",
                CONSUMER_NAME, payload.aggregateId());
            return;
        }

        workOrderWip.rollInSubAssemblies(payload.aggregateId(), total);

        LocalDate postingDate = payload.occurredAt() == null
            ? LocalDate.now()
            : payload.occurredAt().atZone(ZoneId.systemDefault()).toLocalDate();
        journals.postSubAssemblyConsumption(
            payload.aggregateId(),
            payload.aggregateId().toString(),
            lineCosts,
            Currencies.BASE_CURRENCY,
            postingDate
        );
        log.info("[{}] rolled {} consumed sub-assembl(ies) into WIP for parent work_order={} (total={})",
            CONSUMER_NAME, lineCosts.size(), payload.aggregateId(), total);
    }
}
