package com.northwood.testharness.inmemory.purchasing;

import com.northwood.purchasing.application.inbox.PurchaseOrderReceiptProjection;
import com.northwood.purchasing.domain.PurchaseOrder;
import com.northwood.purchasing.domain.PurchaseOrderId;
import com.northwood.purchasing.domain.PurchaseOrderLine;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory {@link PurchaseOrderReceiptProjection}. Tracks per-line received
 * quantity and computes the fully-received outcome by inspecting the in-memory
 * {@link InMemoryPurchaseOrderRepository}.
 */
public final class InMemoryPurchaseOrderReceiptProjection implements PurchaseOrderReceiptProjection {

    private record Key(UUID poHeaderId, UUID poLineId) {}

    private final Map<Key, BigDecimal> receivedByLine = new HashMap<>();
    private final InMemoryPurchaseOrderRepository orders;

    public InMemoryPurchaseOrderReceiptProjection(InMemoryPurchaseOrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public ReceiptOutcome recordReceipt(UUID purchaseOrderHeaderId, List<ReceiptLine> lines) {
        for (ReceiptLine l : lines) {
            BigDecimal qty = l.receivedQuantity() == null ? BigDecimal.ZERO : l.receivedQuantity();
            receivedByLine.merge(new Key(purchaseOrderHeaderId, l.purchaseOrderLineId()), qty, BigDecimal::add);
        }

        // Check fully received against the PO aggregate's line ordered quantities.
        PurchaseOrder po = orders.findById(PurchaseOrderId.of(purchaseOrderHeaderId)).orElse(null);
        if (po == null) {
            return new ReceiptOutcome(false, BigDecimal.ZERO);
        }
        BigDecimal totalReceived = BigDecimal.ZERO;
        boolean fullyReceived = true;
        for (PurchaseOrderLine line : po.lines()) {
            BigDecimal received = receivedByLine.getOrDefault(new Key(purchaseOrderHeaderId, line.id()), BigDecimal.ZERO);
            totalReceived = totalReceived.add(received);
            if (received.compareTo(line.orderedQuantity()) < 0) {
                fullyReceived = false;
            }
        }
        return new ReceiptOutcome(fullyReceived, totalReceived);
    }
}
