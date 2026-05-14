package com.northwood.testharness.inmemory.inventory;

import com.northwood.inventory.application.WipBalanceWriter;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory {@link WipBalanceWriter}. The harness exposes both the
 * (warehouse, product) keying used by {@code bump} and a product-only
 * lookup mirroring {@code decrement} (which doesn't carry warehouse).
 *
 * <p>Underflow on decrement throws — production has a non-negative CHECK
 * on {@code wip_balance.on_hand_quantity}. Test-side asserts the same
 * invariant.
 */
public final class InMemoryWipBalanceWriter implements WipBalanceWriter {

    private record Key(UUID warehouseId, UUID productId) {}

    private final Map<Key, BigDecimal> balances = new HashMap<>();
    private final Map<UUID, BigDecimal> totalsByProduct = new HashMap<>();

    @Override
    public void bump(UUID warehouseId, UUID productId, BigDecimal quantity) {
        balances.merge(new Key(warehouseId, productId), quantity, BigDecimal::add);
        totalsByProduct.merge(productId, quantity, BigDecimal::add);
    }

    @Override
    public void decrement(UUID productId, BigDecimal quantity) {
        BigDecimal current = totalsByProduct.getOrDefault(productId, BigDecimal.ZERO);
        BigDecimal next = current.subtract(quantity);
        if (next.signum() < 0) {
            throw new IllegalStateException(
                "wip_balance underflow for product " + productId + ": current=" + current + ", decrement=" + quantity
            );
        }
        totalsByProduct.put(productId, next);
        // Approximate: drain whichever warehouse(s) have stock for this product.
        // Tests don't typically assert per-warehouse WIP totals after consume.
        for (Map.Entry<Key, BigDecimal> e : balances.entrySet()) {
            if (!productId.equals(e.getKey().productId())) continue;
            BigDecimal remaining = e.getValue();
            if (remaining.signum() <= 0) continue;
            if (remaining.compareTo(quantity) >= 0) {
                e.setValue(remaining.subtract(quantity));
                quantity = BigDecimal.ZERO;
            } else {
                quantity = quantity.subtract(remaining);
                e.setValue(BigDecimal.ZERO);
            }
            if (quantity.signum() <= 0) break;
        }
    }

    /** Test-side: total WIP for a product across all warehouses. */
    public BigDecimal totalFor(UUID productId) {
        return totalsByProduct.getOrDefault(productId, BigDecimal.ZERO);
    }

    /** Test-side: per (warehouse, product) WIP. */
    public BigDecimal at(UUID warehouseId, UUID productId) {
        return balances.getOrDefault(new Key(warehouseId, productId), BigDecimal.ZERO);
    }
}
