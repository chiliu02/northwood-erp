package com.northwood.reporting.application.inbox.dashboard;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.FinancialDashboardProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Records cost of goods sold on the financial dashboard from
 * {@code sales.SalesOrderShipped}. COGS is recognised on shipment (matching
 * principle), mirroring finance's {@code SalesOrderShippedHandler}: per line,
 * {@code shippedQuantity × unitCost}, summed over the non-free-of-charge lines.
 *
 * <p>Free-of-charge lines (zero sale price) are excluded — finance posts their
 * cost to 5500 Promotions, not 5000 COGS, so the dashboard's
 * {@code cost_of_goods_sold} tracks GL account 5000. This replaced the earlier
 * supplier-invoice proxy, which mis-mapped purchases (AP) to COGS and missed
 * COGS entirely for goods sold from existing stock.
 */
@Component("dashboard_SalesOrderShippedHandler")
public class SalesOrderShippedHandler extends AbstractInboxHandler<SalesOrderShipped> {

    public static final String CONSUMER_NAME = "reporting.dashboard.sales-order-shipped";

    private final FinancialDashboardProjection projection;

    public SalesOrderShippedHandler(
        InboxPort inbox,
        FinancialDashboardProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderShipped.class, SalesOrderShipped.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SalesOrderShipped payload, EventEnvelope envelope) {
        BigDecimal cogs = BigDecimal.ZERO;
        if (payload.lines() != null) {
            for (var line : payload.lines()) {
                if (isFreeOfCharge(line)) {
                    continue;
                }
                BigDecimal qty = line.shippedQuantity() == null ? BigDecimal.ZERO : line.shippedQuantity();
                BigDecimal unitCost = line.unitCost() == null ? BigDecimal.ZERO : line.unitCost();
                cogs = cogs.add(qty.multiply(unitCost));
            }
        }
        projection.recordCostOfGoodsSold(cogs, payload.currencyCode(), payload.occurredAt());
    }

    /** Mirrors finance's rule: a zero sale price routes cost to promotions, not COGS. */
    private static boolean isFreeOfCharge(SalesOrderShipped.ShippedLine line) {
        return line.unitPrice() != null && line.unitPrice().signum() == 0;
    }
}
