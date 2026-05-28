package com.northwood.testharness.inmemory.inventory;

import com.northwood.inventory.application.inbox.SalesOrderLineFactsProjection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory backing for {@link SalesOrderLineFactsProjection}. Map keyed on
 * {@code sales_order_line_id}; redelivery is idempotent (put overwrites with
 * the same value). §2.31 Slice C: per-header payment_terms + prepayment_settled
 * are stashed in a parallel map keyed on {@code sales_order_header_id} so the
 * gate check matches the production semantic.
 */
public final class InMemorySalesOrderLineFactsProjection implements SalesOrderLineFactsProjection {

    private final Map<UUID, UUID> productIdByLineId = new HashMap<>();
    private final Map<UUID, UUID> headerByLineId = new HashMap<>();
    private final Map<UUID, String> paymentTermsByHeaderId = new HashMap<>();
    private final Map<UUID, Boolean> prepaymentSettledByHeaderId = new HashMap<>();

    @Override
    public void applySalesOrderPlaced(UUID salesOrderHeaderId, UUID salesOrderLineId, UUID productId, String paymentTerms) {
        productIdByLineId.put(salesOrderLineId, productId);
        headerByLineId.put(salesOrderLineId, salesOrderHeaderId);
        paymentTermsByHeaderId.put(salesOrderHeaderId, paymentTerms == null ? "on_shipment" : paymentTerms);
        prepaymentSettledByHeaderId.putIfAbsent(salesOrderHeaderId, false);
    }

    @Override
    public void applyPrepaymentSettled(UUID salesOrderHeaderId) {
        prepaymentSettledByHeaderId.put(salesOrderHeaderId, true);
    }

    @Override
    public Optional<UUID> findProductIdForLine(UUID salesOrderLineId) {
        return Optional.ofNullable(productIdByLineId.get(salesOrderLineId));
    }

    @Override
    public Optional<PrepaymentGate> findPrepaymentGate(UUID salesOrderHeaderId) {
        String pt = paymentTermsByHeaderId.get(salesOrderHeaderId);
        if (pt == null) {
            return Optional.empty();
        }
        return Optional.of(new PrepaymentGate(
            pt,
            prepaymentSettledByHeaderId.getOrDefault(salesOrderHeaderId, false)
        ));
    }
}
