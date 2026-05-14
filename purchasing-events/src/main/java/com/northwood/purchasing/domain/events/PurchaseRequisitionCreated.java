package com.northwood.purchasing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A new purchase requisition has been created. Carries enough detail for any
 * downstream consumer (e.g. a future approval workflow, a supplier-facing
 * notification) to act without having to query purchasing's schema.
 */
public record PurchaseRequisitionCreated(
    UUID eventId,
    UUID aggregateId,
    String requisitionNumber,
    String sourceType,
    UUID sourceWorkOrderId,
    UUID sourceProductId,
    String status,
    List<RequestedLine> lines,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "purchasing.PurchaseRequisitionCreated";

    @Override public String eventType() { return EVENT_TYPE; }

    public record RequestedLine(
        UUID lineId,
        int lineNumber,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal requestedQuantity,
        UUID suggestedSupplierId
    ) {}
}
