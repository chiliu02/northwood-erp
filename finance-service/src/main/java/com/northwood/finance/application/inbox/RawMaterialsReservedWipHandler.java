package com.northwood.finance.application.inbox;

import com.northwood.finance.application.JournalEntryService;
import com.northwood.finance.application.JournalEntryService.LineCost;
import com.northwood.finance.application.ProductCardLookup;
import com.northwood.inventory.domain.events.RawMaterialsReserved;
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
 * Perpetual WIP. Idempotent inbox handler for
 * {@code inventory.RawMaterialsReserved}: when a work order's materials are
 * fully reserved (issued to production), posts Dr 1230 WIP / Cr 1210 Raw
 * Materials for the sum of {@code reservedQuantity * standardCost} across the
 * components — valued at finance's authoritative {@code product_card.standard_cost}.
 *
 * <p>Only {@code status = "reserved"} (fully reserved) charges WIP; partial /
 * failed reservations park, and the shortage-recovery flow re-reserves once
 * goods arrive — emitting a fresh {@code reserved} event. The
 * {@link WorkOrderWipProjection#chargeRawMaterials} gate makes that re-emission
 * a no-op (charge once per work order).
 *
 * <p>Standard cost missing (projection cold-start) → that line contributes 0;
 * a zero total skips both the sub-ledger charge and the journal (retryable),
 * mirroring the goods-receipt / shipment zero-cost skip.
 */
@Component
public class RawMaterialsReservedWipHandler extends AbstractInboxHandler<RawMaterialsReserved> {

    public static final String CONSUMER_NAME = "finance.wip.raw-materials-reserved";

    private final JournalEntryService journals;
    private final ProductCardLookup productCards;
    private final WorkOrderWipProjection workOrderWip;

    public RawMaterialsReservedWipHandler(
        InboxPort inbox,
        JournalEntryService journals,
        ProductCardLookup productCards,
        WorkOrderWipProjection workOrderWip,
        ObjectMapper json
    ) {
        super(inbox, json, RawMaterialsReserved.class, RawMaterialsReserved.EVENT_TYPE, CONSUMER_NAME);
        this.journals = journals;
        this.productCards = productCards;
        this.workOrderWip = workOrderWip;
    }

    @Override
    protected void apply(RawMaterialsReserved payload, EventEnvelope envelope) {
        if (!RawMaterialsReserved.STATUS_RESERVED.equals(payload.status())) {
            log.debug("[{}] work_order={} status={} — not fully reserved, no WIP charge",
                CONSUMER_NAME, payload.workOrderId(), payload.status());
            return;
        }

        List<LineCost> lineCosts = new ArrayList<>();
        if (payload.components() != null) {
            for (var c : payload.components()) {
                BigDecimal qty = c.reservedQuantity() == null ? BigDecimal.ZERO : c.reservedQuantity();
                BigDecimal stdCost = productCards.findStandardCost(c.componentProductId()).orElse(BigDecimal.ZERO);
                lineCosts.add(new LineCost(c.componentProductId(), qty.multiply(stdCost)));
            }
        }
        BigDecimal total = lineCosts.stream().map(LineCost::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            log.debug("[{}] work_order={} has zero standard-cost materials — skipping WIP charge",
                CONSUMER_NAME, payload.workOrderId());
            return;
        }

        if (!workOrderWip.chargeRawMaterials(payload.workOrderId(), total)) {
            log.debug("[{}] work_order={} raw materials already charged to WIP — skipping",
                CONSUMER_NAME, payload.workOrderId());
            return;
        }

        LocalDate postingDate = payload.occurredAt() == null
            ? LocalDate.now()
            : payload.occurredAt().atZone(ZoneId.systemDefault()).toLocalDate();
        journals.postWorkInProgressCharge(
            payload.workOrderId(),
            payload.workOrderId().toString(),
            lineCosts,
            Currencies.BASE_CURRENCY,
            postingDate
        );
        log.info("[{}] charged raw materials to WIP for work_order={} (total={}, {} line(s))",
            CONSUMER_NAME, payload.workOrderId(), total, lineCosts.size());
    }
}
