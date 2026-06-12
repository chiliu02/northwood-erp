package com.northwood.finance.application.inbox;

import com.northwood.finance.application.CustomerInvoiceService;
import com.northwood.finance.application.JournalEntryService;
import com.northwood.finance.application.PaymentService;
import com.northwood.finance.application.ProductCardLookup;
import com.northwood.finance.domain.CustomerInvoiceId;
import com.northwood.sales.domain.PaymentTerms;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code sales.SalesOrderShipped}. On a shipment it:
 * <ol>
 *   <li>auto-creates the customer invoice (Dr 1100 AR / Cr 4000 Revenue) from the
 *       line snapshots — a zero-total invoice posts no journal (free-of-charge,
 *       see {@code JournalEntryService.post});</li>
 *   <li>for {@code cod} orders, auto-records the full payment (Dr Cash / Cr AR)
 *       in the same transaction;</li>
 *   <li>posts the perpetual-inventory cost of the goods that left stock —
 *       Dr 5000 COGS / Cr Inventory at standard cost, or <b>Dr 5500 Promotions
 *       Expense</b> for any free-of-charge (zero sale price) line.</li>
 * </ol>
 *
 * <p>Recognising COGS on the <em>same</em> event as revenue follows the matching
 * principle, and this is the only event that carries both the per-line <b>sale
 * price</b> (to detect free-of-charge) and the <b>cost</b> (threaded on from
 * inventory's {@code ShipmentPosted}, which doesn't own pricing). Inbox dedupe
 * makes a redelivered shipment neither double-invoice nor double-post.
 *
 * <p><b>Cost source / silent fallback.</b> Line cost uses finance's authoritative
 * {@code product_card.standard_cost}; on a projection cold-start it falls back to
 * the shipment-stamped {@code unitCost} and DEBUG-logs. See {@code design-notes.md}
 * → <i>Documented silent fallbacks</i> (COGS standard cost).
 */
@Component
public class SalesOrderShippedHandler extends AbstractInboxHandler<SalesOrderShipped> {

    public static final String CONSUMER_NAME = "finance.customer-invoice.shipped-order";

    private final CustomerInvoiceService invoices;
    private final PaymentService payments;
    private final JournalEntryService journals;
    private final ProductCardLookup productCards;

    public SalesOrderShippedHandler(
        InboxPort inbox,
        CustomerInvoiceService invoices,
        PaymentService payments,
        JournalEntryService journals,
        ProductCardLookup productCards,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderShipped.class, SalesOrderShipped.EVENT_TYPE, CONSUMER_NAME);
        this.invoices = invoices;
        this.payments = payments;
        this.journals = journals;
        this.productCards = productCards;
    }

    @Override
    protected void apply(SalesOrderShipped payload, EventEnvelope envelope) {
        CustomerInvoiceId invoiceId = invoices.createFromShippedOrder(payload);

        if (PaymentTerms.CASH_ON_DELIVERY.code().equals(payload.paymentTerms())) {
            payments.recordCashOnDeliveryPayment(invoiceId.value(), payload.shipmentDate());
            log.info("[{}] auto-invoiced + COD-settled sales_order={} (shipment={})",
                CONSUMER_NAME, payload.aggregateId(), payload.shipmentNumber());
        } else {
            log.info("[{}] auto-invoiced sales_order={} (shipment={})",
                CONSUMER_NAME, payload.aggregateId(), payload.shipmentNumber());
        }

        postCostOfGoodsShipped(payload);
    }

    /**
     * Dr COGS (or Dr 5500 Promotions for a free-of-charge line) / Cr Inventory at
     * standard cost for the goods that left stock. Fires for every shipment — the
     * inventory reduction is real regardless of payment terms or a zero sale price;
     * only the debit account differs for free-of-charge lines.
     */
    private void postCostOfGoodsShipped(SalesOrderShipped payload) {
        List<JournalEntryService.LineCost> lineCosts = new ArrayList<>();
        int fallbackToShipmentCostLines = 0;
        if (payload.lines() != null) {
            for (var l : payload.lines()) {
                BigDecimal qty = l.shippedQuantity() == null ? BigDecimal.ZERO : l.shippedQuantity();
                BigDecimal eventStampedCost = l.unitCost() == null ? BigDecimal.ZERO : l.unitCost();
                var projected = productCards.findStandardCost(l.productId());
                BigDecimal unitCost = projected.orElse(eventStampedCost);
                if (projected.isEmpty()) {
                    fallbackToShipmentCostLines++;
                }
                boolean freeOfCharge = l.unitPrice() != null && l.unitPrice().signum() == 0;
                lineCosts.add(new JournalEntryService.LineCost(l.productId(), qty.multiply(unitCost), freeOfCharge));
            }
        }
        if (fallbackToShipmentCostLines > 0) {
            log.debug("[{}] shipment {} had {} line(s) with no product_card.standard_cost — fell back to "
                    + "shipment-stamped unitCost (projection cold-start). See design-notes.md → COGS standard cost.",
                CONSUMER_NAME, payload.shipmentNumber(), fallbackToShipmentCostLines);
        }
        journals.postShipmentCost(
            payload.shipmentHeaderId(),
            payload.shipmentNumber(),
            lineCosts,
            payload.currencyCode(),
            payload.shipmentDate()
        );
    }
}
