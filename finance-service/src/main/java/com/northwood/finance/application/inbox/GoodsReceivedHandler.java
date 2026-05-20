package com.northwood.finance.application.inbox;

import com.northwood.finance.application.JournalEntryService;
import com.northwood.inventory.domain.events.GoodsReceived;
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
 * Idempotent inbox handler for {@code inventory.GoodsReceived}. Two
 * responsibilities in one transaction:
 *
 * <ol>
 *   <li>Bump {@code received_quantity} on the matching
 *       {@code purchase_order_line_facts} row so 3-way match knows how
 *       much has actually arrived.</li>
 *   <li>Post the perpetual-inventory GL pair Dr 1200 Inventory / Cr 1300
 *       GRNI for the total receipt cost (sum of
 *       {@code receivedQuantity * unitCost} across the receipt's lines).
 *       This is the moment cost moves onto the balance sheet under
 *       perpetual inventory; it stays there until shipment moves it to
 *       COGS.</li>
 * </ol>
 */
@Component
public class GoodsReceivedHandler extends AbstractInboxHandler<GoodsReceived> {

    public static final String CONSUMER_NAME = "finance.po-line-facts.goods-received";

    private final PurchaseOrderLineFactsProjection projection;
    private final JournalEntryService journals;

    public GoodsReceivedHandler(
        InboxPort inbox,
        PurchaseOrderLineFactsProjection projection,
        JournalEntryService journals,
        ObjectMapper json
    ) {
        super(inbox, json, GoodsReceived.class, GoodsReceived.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
        this.journals = journals;
    }

    @Override
    protected void apply(GoodsReceived payload, EventEnvelope envelope) {
        java.util.List<JournalEntryService.LineCost> lineCosts = new java.util.ArrayList<>();
        for (GoodsReceived.ReceivedLine rl : payload.lines()) {
            projection.applyGoodsReceived(rl.purchaseOrderLineId(), rl.receivedQuantity());
            BigDecimal qty = rl.receivedQuantity() == null ? BigDecimal.ZERO : rl.receivedQuantity();
            BigDecimal cost = rl.unitCost() == null ? BigDecimal.ZERO : rl.unitCost();
            lineCosts.add(new JournalEntryService.LineCost(rl.productId(), qty.multiply(cost)));
        }
        // Perpetual-inventory GL: Dr <per-class inventory account> / Cr 1300
        // GRNI for the receipt cost. §3.2 splits the inventory debit per
        // valuation class (raw_materials → 1210, finished_goods → 1220).
        // Posted in the same txn as the projection update so failures roll
        // back together.
        LocalDate postingDate = payload.occurredAt() == null
            ? LocalDate.now()
            : payload.occurredAt().atZone(ZoneId.systemDefault()).toLocalDate();
        journals.postGoodsReceived(
            payload.aggregateId(),
            payload.goodsReceiptNumber(),
            lineCosts,
            Currencies.BASE_CURRENCY,
            postingDate
        );

        log.info("[{}] applied receipt {} for purchase_order={} ({} line(s))",
            CONSUMER_NAME, payload.goodsReceiptNumber(),
            payload.purchaseOrderHeaderId(), payload.lines().size());
    }
}
