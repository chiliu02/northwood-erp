package com.northwood.finance.application.inbox;

import com.northwood.finance.application.JournalEntryService;
import com.northwood.manufacturing.domain.events.WorkOrderConversionApplied;
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
 * Perpetual WIP. Idempotent inbox handler for
 * {@code manufacturing.WorkOrderConversionApplied}: posts Dr 1230 WIP /
 * Cr 5250 Conversion Cost Applied for the work order's standard conversion
 * cost (labour + overhead). The third charge into WIP — with raw materials
 * ({@link RawMaterialsReservedWipHandler}) and consumed sub-assemblies
 * ({@link SubAssembliesConsumedWipHandler}) in, and the FG receipt
 * ({@link WorkOrderManufacturingCompletedWipHandler}, crediting WIP at the full
 * standard cost = material + conversion) out — WIP nets to zero per work order.
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
        String currency = payload.currencyCode() == null ? Currencies.BASE_CURRENCY : payload.currencyCode();

        // Charge WIP at ACTUAL conversion (Dr 1230 / Cr 5250) ...
        journals.postConversionCharge(
            payload.aggregateId(),
            payload.workOrderNumber(),
            payload.actualConversionCost(),
            currency,
            postingDate
        );
        // ... then clear the efficiency variance (actual − standard) to 5100, so
        // WIP nets to zero against the standard-cost FG receipt.
        BigDecimal variance = nullToZero(payload.actualConversionCost())
            .subtract(nullToZero(payload.standardConversionCost()));
        journals.postProductionVariance(
            payload.aggregateId(),
            payload.workOrderNumber(),
            variance,
            currency,
            postingDate
        );
        log.info("[{}] applied conversion to WIP for work_order={} actual={} standard={} variance={}",
            CONSUMER_NAME, payload.aggregateId(),
            payload.actualConversionCost(), payload.standardConversionCost(), variance);
    }

    private static BigDecimal nullToZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
