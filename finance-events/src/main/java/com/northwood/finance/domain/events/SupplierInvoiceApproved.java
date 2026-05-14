package com.northwood.finance.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A supplier invoice has passed 3-way match and been auto-approved. Carries
 * the keys downstream consumers need: purchasing's P2P saga advances to
 * {@code supplier_invoice_approved} when this event arrives.
 *
 * <p>Phase 4 only emits this event on full match success. Match-failed
 * invoices stay at {@code three_way_match_failed} for manual review and emit
 * no event yet — manual review tooling lands in a later slice.
 */
public record SupplierInvoiceApproved(
    UUID eventId,
    UUID aggregateId,
    String internalInvoiceNumber,
    String supplierInvoiceNumber,
    UUID purchaseOrderHeaderId,
    UUID supplierId,
    String supplierName,
    String currencyCode,
    BigDecimal totalAmount,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "finance.SupplierInvoiceApproved";

    @Override public String eventType() { return EVENT_TYPE; }
}
