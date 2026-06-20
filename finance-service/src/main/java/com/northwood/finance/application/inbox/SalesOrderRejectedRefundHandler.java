package com.northwood.finance.application.inbox;

import com.northwood.finance.application.CustomerCancellationRefundService;
import com.northwood.sales.domain.events.SalesOrderRejected;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Refunds a rejected order's paid prepayment/deposit on {@code sales.SalesOrderRejected}
 * — the confirmed non-shippable terminal for an unsourceable order. A reject is always
 * safe to refund (a rejected order was never reserved, so it can never ship), so this
 * is the reject counterpart of {@link SalesOrderCompensatedRefundHandler}. Delegates to
 * {@link CustomerCancellationRefundService}.
 */
@Component
public class SalesOrderRejectedRefundHandler extends AbstractInboxHandler<SalesOrderRejected> {

    public static final String HANDLER_NAME = "finance.refund.sales-order-rejected";

    private final CustomerCancellationRefundService refunds;

    public SalesOrderRejectedRefundHandler(
        InboxPort inbox,
        CustomerCancellationRefundService refunds,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderRejected.class, SalesOrderRejected.EVENT_TYPE, HANDLER_NAME);
        this.refunds = refunds;
    }

    @Override
    protected void apply(SalesOrderRejected payload, EventEnvelope envelope) {
        refunds.refundUpfrontIfPaid(payload.aggregateId(), payload.occurredAt());
    }
}
