package com.northwood.testharness.inmemory.inventory;

import com.northwood.inventory.application.inbox.PurchaseOrderLineFactsProjection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory double of inventory's {@link PurchaseOrderLineFactsProjection}
 * for the harness scenarios. Backs {@code PurchaseOrderCreatedHandler} so it
 * can be wired in {@code InventoryTestKit} (the handler's other responsibility
 * — bridging the PR→PO link to the replenishment request — is what the harness
 * tests exercise).
 *
 * <p>Sibling to {@code testharness.inmemory.finance.InMemoryPurchaseOrderLineFactsProjection}
 * — same shape, different schema. The harness can't share one because the
 * two services own distinct {@code PurchaseOrderLineFactsProjection}
 * interfaces under their own packages.
 */
public final class InMemoryInventoryPurchaseOrderLineFactsProjection
    implements PurchaseOrderLineFactsProjection {

    private final Map<UUID, UUID> productIdByLineId = new HashMap<>();

    @Override
    public void applyPurchaseOrderCreated(
        UUID purchaseOrderHeaderId,
        UUID purchaseOrderLineId,
        UUID productId
    ) {
        productIdByLineId.put(purchaseOrderLineId, productId);
    }

    @Override
    public Optional<UUID> findProductIdForLine(UUID purchaseOrderLineId) {
        return Optional.ofNullable(productIdByLineId.get(purchaseOrderLineId));
    }
}
