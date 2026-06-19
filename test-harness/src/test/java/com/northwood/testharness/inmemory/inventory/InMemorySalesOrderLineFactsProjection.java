package com.northwood.testharness.inmemory.inventory;

import com.northwood.inventory.application.inbox.SalesOrderLineFactsProjection;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory backing for {@link SalesOrderLineFactsProjection}. Map keyed on
 * {@code sales_order_line_id}; redelivery is idempotent (put overwrites with
 * the same value). Per-header payment_terms +
 * upfront_settled are stashed in a parallel map keyed on
 * {@code sales_order_header_id} so the gate check matches the production semantic.
 */
public final class InMemorySalesOrderLineFactsProjection implements SalesOrderLineFactsProjection {

    private final Map<UUID, UUID> productIdByLineId = new HashMap<>();
    private final Map<UUID, UUID> headerByLineId = new HashMap<>();
    private final Map<UUID, String> paymentTermsByHeaderId = new HashMap<>();
    private final Map<UUID, Boolean> upfrontSettledByHeaderId = new HashMap<>();
    private final Map<UUID, BigDecimal> orderedByLineId = new HashMap<>();
    private final Map<UUID, BigDecimal> shippedByLineId = new HashMap<>();

    @Override
    public void applySalesOrderPlaced(UUID salesOrderHeaderId, UUID salesOrderLineId, UUID productId,
                                      BigDecimal orderedQuantity, String paymentTerms) {
        productIdByLineId.put(salesOrderLineId, productId);
        headerByLineId.put(salesOrderLineId, salesOrderHeaderId);
        paymentTermsByHeaderId.put(salesOrderHeaderId, paymentTerms == null ? "on_shipment" : paymentTerms);
        upfrontSettledByHeaderId.putIfAbsent(salesOrderHeaderId, false);
        orderedByLineId.put(salesOrderLineId, orderedQuantity);
        shippedByLineId.putIfAbsent(salesOrderLineId, BigDecimal.ZERO);
    }

    @Override
    public void applyLineQuantityChanged(UUID salesOrderLineId, BigDecimal newOrderedQuantity) {
        if (orderedByLineId.containsKey(salesOrderLineId)) {
            orderedByLineId.put(salesOrderLineId, newOrderedQuantity);
        }
    }

    @Override
    public void applyLineRemoved(UUID salesOrderLineId) {
        if (shippedByLineId.getOrDefault(salesOrderLineId, BigDecimal.ZERO).signum() == 0) {
            orderedByLineId.put(salesOrderLineId, BigDecimal.ZERO);
        }
    }

    @Override
    public synchronized boolean tryClaimShipment(UUID salesOrderLineId, BigDecimal quantity) {
        BigDecimal ordered = orderedByLineId.get(salesOrderLineId);
        if (ordered == null) {
            return false;
        }
        BigDecimal shipped = shippedByLineId.getOrDefault(salesOrderLineId, BigDecimal.ZERO);
        if (shipped.add(quantity).compareTo(ordered) > 0) {
            return false;
        }
        shippedByLineId.put(salesOrderLineId, shipped.add(quantity));
        return true;
    }

    @Override
    public void applyUpfrontPaymentSettled(UUID salesOrderHeaderId) {
        upfrontSettledByHeaderId.put(salesOrderHeaderId, true);
    }

    @Override
    public Optional<UUID> findProductIdForLine(UUID salesOrderLineId) {
        return Optional.ofNullable(productIdByLineId.get(salesOrderLineId));
    }

    @Override
    public Optional<UpfrontPaymentGate> findUpfrontPaymentGate(UUID salesOrderHeaderId) {
        String pt = paymentTermsByHeaderId.get(salesOrderHeaderId);
        if (pt == null) {
            return Optional.empty();
        }
        return Optional.of(new UpfrontPaymentGate(
            pt,
            upfrontSettledByHeaderId.getOrDefault(salesOrderHeaderId, false)
        ));
    }
}
