package com.northwood.finance.application.inbox;

import com.northwood.finance.application.JournalEntryService;
import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code inventory.ShipmentPosted}. Posts
 * the perpetual-inventory COGS pair Dr 5000 COGS / Cr 1200 Inventory for
 * the total shipment cost (sum of {@code shippedQuantity * standardCost}
 * across the shipment's lines). This is the moment cost actually hits
 * the income statement under perpetual inventory.
 *
 * <p>Distinct from the existing {@code SalesOrderShippedHandler} (which
 * auto-creates the customer invoice from sales' richer event) — this
 * handler subscribes to inventory's own ShipmentPosted. Both handlers fire
 * on a shipment; one drives AR/Revenue, this one drives COGS/Inventory.
 *
 * <p><b>§2.8 Slice B — cost source.</b> The line cost used for COGS comes
 * from finance's {@code product_standard_cost} projection (fed by
 * {@code product.StandardCostChanged} via {@link StandardCostChangedHandler}).
 * That's finance's authoritative number — independent of whatever
 * {@code unitCost} the warehouse clerk typed onto the shipment line.
 *
 * <p><b>Silent-fallback contract on projection cold-start.</b> If
 * {@code findStandardCost} returns empty for a productId — extremely rare
 * because the projection is seeded from product baseline values via
 * Liquibase + maintained by the inbox handler — we fall back to the
 * shipment-line-stamped {@code unitCost} and emit a DEBUG log. This keeps
 * the GL flowing on a fresh-volume boot before the seed runs and on the
 * unlikely event-stream race where an earlier shipment beats the seed.
 * See {@code design-notes.md} (Documented silent fallbacks → COGS standard
 * cost) for the full rationale + tightening alternative.
 */
@Component
public class ShipmentPostedCogsHandler extends AbstractInboxHandler<ShipmentPosted> {

    public static final String CONSUMER_NAME = "finance.cogs.shipment-posted";

    private final JournalEntryService journals;
    private final ProductStandardCostProjection standardCosts;

    public ShipmentPostedCogsHandler(
        InboxPort inbox,
        JournalEntryService journals,
        ProductStandardCostProjection standardCosts,
        ObjectMapper json
    ) {
        super(inbox, json, ShipmentPosted.class, ShipmentPosted.EVENT_TYPE, CONSUMER_NAME);
        this.journals = journals;
        this.standardCosts = standardCosts;
    }

    @Override
    protected void apply(ShipmentPosted payload, EventEnvelope envelope) {
        java.util.List<JournalEntryService.LineCost> lineCosts = new java.util.ArrayList<>();
        int fallbackToShipmentCostLines = 0;
        if (payload.lines() != null) {
            for (var l : payload.lines()) {
                BigDecimal qty = l.shippedQuantity() == null ? BigDecimal.ZERO : l.shippedQuantity();
                BigDecimal eventStampedCost = l.unitCost() == null ? BigDecimal.ZERO : l.unitCost();
                java.util.Optional<BigDecimal> projected = standardCosts.findStandardCost(l.productId());
                BigDecimal unitCost = projected.orElse(eventStampedCost);
                if (projected.isEmpty()) {
                    fallbackToShipmentCostLines++;
                }
                lineCosts.add(new JournalEntryService.LineCost(l.productId(), qty.multiply(unitCost)));
            }
        }
        if (fallbackToShipmentCostLines > 0) {
            log.debug(
                "[{}] shipment {} encountered {} line(s) with no product_standard_cost row — "
                    + "fell back to shipment-line-stamped unitCost (projection cold-start). See "
                    + "design-notes.md → COGS standard cost.",
                CONSUMER_NAME, payload.shipmentNumber(), fallbackToShipmentCostLines
            );
        }
        LocalDate postingDate = payload.occurredAt() == null
            ? LocalDate.now()
            : payload.occurredAt().atZone(ZoneId.systemDefault()).toLocalDate();
        journals.postShipmentCost(
            payload.aggregateId(),
            payload.shipmentNumber(),
            lineCosts,
            "AUD",
            postingDate
        );

        log.info("[{}] posted COGS for shipment {} ({} line(s))",
            CONSUMER_NAME, payload.shipmentNumber(), lineCosts.size());
    }
}
