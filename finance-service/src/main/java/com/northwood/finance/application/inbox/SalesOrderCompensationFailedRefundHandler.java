package com.northwood.finance.application.inbox;

import com.northwood.finance.application.CustomerCancellationRefundService;
import com.northwood.sales.domain.events.SalesOrderCompensationFailed;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Refunds a cancelled order's paid prepayment/deposit on
 * {@code sales.SalesOrderCompensationFailed}. The order was cancelled cleanly even
 * though an order-pegged supply leg could not be withdrawn (the residue is an ops
 * concern, not a different order outcome), so the deposit is still returned.
 * Delegates to {@link CustomerCancellationRefundService}.
 */
@Component
public class SalesOrderCompensationFailedRefundHandler extends AbstractInboxHandler<SalesOrderCompensationFailed> {

    public static final String HANDLER_NAME = "finance.refund.sales-order-compensation-failed";

    private final CustomerCancellationRefundService refunds;

    public SalesOrderCompensationFailedRefundHandler(
        InboxPort inbox,
        CustomerCancellationRefundService refunds,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderCompensationFailed.class, SalesOrderCompensationFailed.EVENT_TYPE, HANDLER_NAME);
        this.refunds = refunds;
    }

    @Override
    protected void apply(SalesOrderCompensationFailed payload, EventEnvelope envelope) {
        refunds.refundUpfrontIfPaid(payload.aggregateId(), payload.occurredAt());
    }
}
