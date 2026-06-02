package com.northwood.finance.application.inbox;

import com.northwood.finance.application.JournalEntryService;
import com.northwood.finance.application.ProductCardLookup;
import com.northwood.inventory.domain.events.StockAdjusted;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code inventory.StockAdjusted}. Posts
 * the inventory-adjustment journal entry: an inventory gain Dr's the product's
 * inventory account / Cr's 5400 Inventory Adjustment; a loss is the reverse.
 * An inventory value change is never off-book.
 *
 * <p><b>Cost source.</b> Inventory doesn't know cost, so the adjustment is
 * valued at finance's authoritative {@code product_card.standard_cost} (fed by
 * {@code product.StandardCostChanged}), like the COGS handler.
 *
 * <p><b>Silent-fallback contract on projection cold-start.</b> If
 * {@code findStandardCost} returns empty (the projection hasn't caught up on a
 * fresh-volume boot, or the product was created but never assigned a standard
 * cost), there is no event-stamped cost to fall back to (unlike
 * {@link ShipmentPostedCogsHandler}, where the shipment line carries a unit
 * cost) — so the amount is zero and {@code postStockAdjustment} skips the GL
 * pair, with a DEBUG log. The inventory move still stands; the GL is reconciled
 * once the projection populates and the adjustment is re-driven or re-posted.
 * See {@code design-notes.md} (Documented silent fallbacks → stock-adjustment
 * standard cost) for the rationale + tightening alternative.
 */
@Component
public class StockAdjustedHandler extends AbstractInboxHandler<StockAdjusted> {

    public static final String CONSUMER_NAME = "finance.gl.stock-adjusted";

    private final JournalEntryService journals;
    private final ProductCardLookup productCards;

    public StockAdjustedHandler(
        InboxPort inbox,
        JournalEntryService journals,
        ProductCardLookup productCards,
        ObjectMapper json
    ) {
        super(inbox, json, StockAdjusted.class, StockAdjusted.EVENT_TYPE, CONSUMER_NAME);
        this.journals = journals;
        this.productCards = productCards;
    }

    @Override
    protected void apply(StockAdjusted payload, EventEnvelope envelope) {
        BigDecimal qty = payload.quantity() == null ? BigDecimal.ZERO : payload.quantity();
        Optional<BigDecimal> standardCost = productCards.findStandardCost(payload.productId());
        if (standardCost.isEmpty()) {
            log.debug(
                "[{}] stock adjustment {} for product {} has no product_card.standard_cost yet — "
                    + "GL post skipped (projection cold-start). See design-notes.md → stock-adjustment standard cost.",
                CONSUMER_NAME, payload.adjustmentNumber(), payload.productId()
            );
        }
        BigDecimal amount = qty.multiply(standardCost.orElse(BigDecimal.ZERO));
        boolean gain = StockAdjusted.DIRECTION_IN.equals(payload.direction());
        LocalDate postingDate = payload.occurredAt() == null
            ? LocalDate.now()
            : payload.occurredAt().atZone(ZoneId.systemDefault()).toLocalDate();

        journals.postStockAdjustment(
            payload.aggregateId(),
            payload.adjustmentNumber(),
            payload.productId(),
            amount,
            gain,
            Currencies.BASE_CURRENCY,
            postingDate
        );

        log.info("[{}] posted GL for stock adjustment {} ({} {} @ standard cost)",
            CONSUMER_NAME, payload.adjustmentNumber(), payload.direction(), qty.toPlainString());
    }
}
