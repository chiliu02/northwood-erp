package com.northwood.testharness.inmemory.inventory;

import com.northwood.inventory.application.StockBalanceLookup;
import com.northwood.inventory.application.StockBalanceWriter;
import com.northwood.inventory.application.dto.StockBalanceView;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Combined in-memory implementation of {@link StockBalanceWriter} +
 * {@link StockBalanceLookup}. The shared map mirrors the production schema's
 * ({@code on_hand_quantity}, {@code reserved_quantity}) row per (warehouse, product).
 *
 * <p>Test setup seeds on-hand quantities via {@link #seedOnHand}.
 */
public final class InMemoryStockBalances implements StockBalanceWriter, StockBalanceLookup {

    private record Key(UUID warehouseId, UUID productId) {}
    private static final class Balance {
        BigDecimal onHand = BigDecimal.ZERO;
        BigDecimal reserved = BigDecimal.ZERO;
    }

    private final Map<Key, Balance> balances = new HashMap<>();

    public InMemoryStockBalances seedOnHand(UUID warehouseId, UUID productId, BigDecimal onHand) {
        balances.computeIfAbsent(new Key(warehouseId, productId), k -> new Balance()).onHand = onHand;
        return this;
    }

    @Override
    public BigDecimal findAvailableQuantity(UUID warehouseId, UUID productId) {
        Balance b = balances.get(new Key(warehouseId, productId));
        if (b == null) return BigDecimal.ZERO;
        return b.onHand.subtract(b.reserved);
    }

    @Override
    public Optional<StockBalanceView> findBalance(UUID warehouseId, UUID productId) {
        Balance b = balances.get(new Key(warehouseId, productId));
        if (b == null) return Optional.empty();
        return Optional.of(new StockBalanceView(
            warehouseId, productId, b.onHand, b.reserved, b.onHand.subtract(b.reserved)));
    }

    @Override
    public void bump(UUID warehouseId, UUID productId, BigDecimal quantity) {
        balances.computeIfAbsent(new Key(warehouseId, productId), k -> new Balance()).onHand =
            balances.get(new Key(warehouseId, productId)).onHand.add(quantity);
    }

    @Override
    public void decrementOnHandAndReleaseReserved(UUID warehouseId, UUID productId, BigDecimal shippedQty) {
        Balance b = balances.get(new Key(warehouseId, productId));
        if (b == null) return;
        b.onHand = b.onHand.subtract(shippedQty);
        BigDecimal release = b.reserved.min(shippedQty);
        b.reserved = b.reserved.subtract(release);
    }

    @Override
    public boolean decrementOnHand(UUID warehouseId, UUID productId, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) return false;
        Balance b = balances.get(new Key(warehouseId, productId));
        if (b == null) return false;
        // Guard: on_hand - q must stay >= reserved (mirrors the JDBC writer); leave reserved alone.
        if (b.onHand.subtract(quantity).compareTo(b.reserved) < 0) return false;
        b.onHand = b.onHand.subtract(quantity);
        return true;
    }

    @Override
    public boolean tryReserveOnHand(UUID warehouseId, UUID productId, BigDecimal quantity) {
        Balance b = balances.get(new Key(warehouseId, productId));
        if (b == null) return false;
        BigDecimal free = b.onHand.subtract(b.reserved);
        if (free.compareTo(quantity) < 0) return false;
        b.reserved = b.reserved.add(quantity);
        return true;
    }

    @Override
    public void releaseReserved(UUID warehouseId, UUID productId, BigDecimal quantity) {
        Balance b = balances.get(new Key(warehouseId, productId));
        if (b == null) return;
        BigDecimal release = b.reserved.min(quantity);
        b.reserved = b.reserved.subtract(release);
    }
}
