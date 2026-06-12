package com.northwood.finance.application.inbox;

import com.northwood.finance.application.JournalEntryService;
import com.northwood.manufacturing.domain.events.WorkOrderConversionApplied;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.domain.Currencies;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Perpetual WIP. Idempotent inbox handler for
 * {@code manufacturing.WorkOrderConversionApplied}: posts Dr 1230 WIP /
 * Cr 5250 Conversion Cost Applied for the work order's standard conversion
 * cost (labour + overhead). The third charge into WIP — with raw materials
 * ({@link RawMaterialsReservedWipHandler}) and consumed sub-assemblies
 * ({@link SubAssembliesConsumedWipHandler}) in, and the FG receipt
 * ({@link WorkOrderManufacturingCompletedWipHandler}, crediting WIP at the full
 * standard cost = material + conversion) out — WIP nets to zero per work order
 * (dev-todo §2.42).
 *
 * <p>Amount is carried on the event (manufacturing computed it from the
 * product's routing × work-centre rates — the same calculation that produced
 * the conversion component of the standard cost). Idempotency is the inbox
 * dedup ({@link AbstractInboxHandler}): the event is emitted exactly once per
 * work-order completion, so no per-WO sub-ledger gate is needed.
 */
@Component
public class WorkOrderConversionAppliedHandler extends AbstractInboxHandler<WorkOrderConversionApplied> {

    public static final String CONSUMER_NAME = "finance.wip.work-order-conversion-applied";

    private final JournalEntryService journals;

    public WorkOrderConversionAppliedHandler(InboxPort inbox, JournalEntryService journals, ObjectMapper json) {
        super(inbox, json,
            WorkOrderConversionApplied.class,
            WorkOrderConversionApplied.EVENT_TYPE,
            CONSUMER_NAME);
        this.journals = journals;
    }

    @Override
    protected void apply(WorkOrderConversionApplied payload, EventEnvelope envelope) {
        LocalDate postingDate = payload.occurredAt() == null
            ? LocalDate.now()
            : payload.occurredAt().atZone(ZoneId.systemDefault()).toLocalDate();
        journals.postConversionCharge(
            payload.aggregateId(),
            payload.workOrderNumber(),
            payload.conversionCost(),
            payload.currencyCode() == null ? Currencies.BASE_CURRENCY : payload.currencyCode(),
            postingDate
        );
        log.info("[{}] applied conversion to WIP for work_order={} amount={}",
            CONSUMER_NAME, payload.aggregateId(), payload.conversionCost());
    }
}
