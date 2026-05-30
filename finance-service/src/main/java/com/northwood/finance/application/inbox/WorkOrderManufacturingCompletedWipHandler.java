package com.northwood.finance.application.inbox;

import com.northwood.finance.application.JournalEntryService;
import com.northwood.finance.application.ProductCardLookup;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.42 Perpetual WIP. Idempotent inbox handler for
 * {@code manufacturing.WorkOrderManufacturingCompleted}: posts Dr 1220 Finished
 * Goods / Cr 1230 WIP at the finished good's standard cost
 * ({@code completedQuantity * standardCost}). This is the leg that empties WIP;
 * because every charge into WIP was at standard cost too, WIP nets to zero per
 * work order (no variance accounts in the material-only cut).
 *
 * <p>Fires for both top-level and sub-assembly-child completions — a completed
 * sub-assembly is finished goods (semi-finished → 1220) until its parent
 * consumes it ({@link SubAssembliesConsumedWipHandler}). Idempotency: the
 * {@link WorkOrderWipProjection#markCompleted} gate stamps {@code completed_at}
 * once.
 */
@Component
public class WorkOrderManufacturingCompletedWipHandler
    extends AbstractInboxHandler<WorkOrderManufacturingCompleted> {

    public static final String CONSUMER_NAME = "finance.wip.work-order-completed";

    private final JournalEntryService journals;
    private final ProductCardLookup productCards;
    private final WorkOrderWipProjection workOrderWip;

    public WorkOrderManufacturingCompletedWipHandler(
        InboxPort inbox,
        JournalEntryService journals,
        ProductCardLookup productCards,
        WorkOrderWipProjection workOrderWip,
        ObjectMapper json
    ) {
        super(inbox, json,
            WorkOrderManufacturingCompleted.class,
            WorkOrderManufacturingCompleted.EVENT_TYPE,
            CONSUMER_NAME);
        this.journals = journals;
        this.productCards = productCards;
        this.workOrderWip = workOrderWip;
    }

    @Override
    protected void apply(WorkOrderManufacturingCompleted payload, EventEnvelope envelope) {
        BigDecimal qty = payload.completedQuantity() == null ? BigDecimal.ZERO : payload.completedQuantity();
        BigDecimal stdCost = productCards.findStandardCost(payload.finishedProductId()).orElse(BigDecimal.ZERO);
        BigDecimal amount = qty.multiply(stdCost);
        if (amount.signum() <= 0) {
            log.debug("[{}] work_order={} finished_product={} has zero standard-cost value — skipping WIP settlement",
                CONSUMER_NAME, payload.aggregateId(), payload.finishedProductId());
            return;
        }

        if (!workOrderWip.markCompleted(payload.aggregateId(), payload.finishedProductId())) {
            log.debug("[{}] work_order={} WIP already settled at completion — skipping",
                CONSUMER_NAME, payload.aggregateId());
            return;
        }

        LocalDate postingDate = payload.occurredAt() == null
            ? LocalDate.now()
            : payload.occurredAt().atZone(ZoneId.systemDefault()).toLocalDate();
        journals.postWorkOrderCompletion(
            payload.aggregateId(),
            payload.workOrderNumber(),
            payload.finishedProductId(),
            amount,
            Currencies.BASE_CURRENCY,
            postingDate
        );
        log.info("[{}] settled WIP at completion for work_order={} ({} @ std cost, amount={})",
            CONSUMER_NAME, payload.workOrderNumber(), qty, amount);
    }
}
