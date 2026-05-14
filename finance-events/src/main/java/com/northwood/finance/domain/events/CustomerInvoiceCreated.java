package com.northwood.finance.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer invoice has been auto-created from {@code sales.SalesOrderShipped}.
 * Status is {@code 'posted'} immediately for showcase simplicity (real-world
 * AR would have a {@code 'draft'} → review → {@code 'posted'} transition).
 *
 * <p>Sales' fulfilment saga consumes this and advances
 * {@code goods_shipped → invoice_created (current_step=wait_for_payment)}.
 */
public record CustomerInvoiceCreated(
    UUID eventId,
    UUID aggregateId,
    String invoiceNumber,
    UUID salesOrderHeaderId,
    UUID customerId,
    String customerCode,
    String customerName,
    String currencyCode,
    BigDecimal totalAmount,
    String status,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "finance.CustomerInvoiceCreated";

    @Override public String eventType() { return EVENT_TYPE; }
}
