package com.northwood.finance.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A supplier invoice parked at {@code three_way_match_failed} has been
 * manually rejected by a reviewer (e.g. supplier sent the wrong invoice;
 * goods don't match). The invoice flips to {@code cancelled}; purchasing's
 * P2P saga consumer transitions the saga to {@code failed} so it stops
 * being polled and lands in a terminal state.
 *
 * <p>Twin of {@link SupplierInvoiceApproved}: the auto-match-success and
 * manual-approve paths both emit {@code SupplierInvoiceApproved}; the
 * manual-reject path emits this event instead. There is no parallel event
 * for the auto-match-failed path because that's a hold-for-review (not a
 * terminal decision) — the reviewer is the one who turns it into either an
 * approval or a rejection.
 */
public record SupplierInvoiceRejected(
    UUID eventId,
    UUID aggregateId,
    String internalInvoiceNumber,
    String supplierInvoiceNumber,
    UUID purchaseOrderHeaderId,
    UUID supplierId,
    String supplierName,
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "finance.SupplierInvoiceRejected";

    @Override public String eventType() { return EVENT_TYPE; }
}
