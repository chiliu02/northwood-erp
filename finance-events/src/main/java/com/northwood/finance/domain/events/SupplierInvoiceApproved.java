package com.northwood.finance.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A supplier invoice has been approved — either automatically via 3-way
 * match (full quantity + price match) or manually by a reviewer who
 * overrode a parked {@code three_way_match_failed} invoice. Purchasing's
 * P2P saga consumer advances to {@code supplier_invoice_approved}.
 *
 * <p>Twin event: {@link SupplierInvoiceRejected} is emitted when a parked
 * invoice is manually rejected. Match-failed invoices that are still
 * awaiting human triage stay at {@code three_way_match_failed} and emit
 * neither event until the reviewer decides.
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
