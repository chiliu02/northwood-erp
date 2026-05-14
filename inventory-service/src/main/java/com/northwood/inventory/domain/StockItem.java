package com.northwood.inventory.domain;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Inventory-side aggregate for a SKU. The non-authoritative descriptive columns
 * (sku, name, type, base UoM) are projected from product master. Reorder policy
 * is also a projection from product (Shape A) — kept in sync from
 * {@code product.ReorderPolicyChanged}. Tracking mode is owned by inventory.
 *
 * <p>For now the only mutator the inventory side needs is
 * {@link #applyReorderPolicy(BigDecimal, BigDecimal)}, invoked by the inbox
 * handler. Future inventory-originated facts (stock adjustments, reservations)
 * will add intent-named methods that emit local domain events to the outbox.
 */
public class StockItem {

    private final StockItemId id;
    private final UUID productId;
    private String productSku;
    private String productName;
    private String productType;
    private String baseUomCode;
    private StockTrackingMode trackingMode;
    private BigDecimal reorderPoint;
    private BigDecimal reorderQuantity;
    private final long version;

    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /** Reconstitute from persistence; does NOT emit events. */
    public static StockItem reconstitute(
        StockItemId id,
        UUID productId,
        String productSku,
        String productName,
        String productType,
        String baseUomCode,
        StockTrackingMode trackingMode,
        BigDecimal reorderPoint,
        BigDecimal reorderQuantity,
        long version
    ) {
        return new StockItem(
            id, productId, productSku, productName, productType, baseUomCode,
            trackingMode, reorderPoint, reorderQuantity, version
        );
    }

    private StockItem(
        StockItemId id,
        UUID productId,
        String productSku,
        String productName,
        String productType,
        String baseUomCode,
        StockTrackingMode trackingMode,
        BigDecimal reorderPoint,
        BigDecimal reorderQuantity,
        long version
    ) {
        this.id = id;
        this.productId = productId;
        this.productSku = productSku;
        this.productName = productName;
        this.productType = productType;
        this.baseUomCode = baseUomCode;
        this.trackingMode = trackingMode;
        this.reorderPoint = reorderPoint;
        this.reorderQuantity = reorderQuantity;
        this.version = version;
    }

    /**
     * Project a {@code ReorderPolicyChanged} fact from product master. No
     * inventory-side event is emitted: this is a downstream projection, not a
     * new business fact.
     */
    public void applyReorderPolicy(BigDecimal newReorderPoint, BigDecimal newReorderQuantity) {
        Objects.requireNonNull(newReorderPoint, "reorderPoint");
        Objects.requireNonNull(newReorderQuantity, "reorderQuantity");
        if (newReorderPoint.signum() < 0) {
            throw new IllegalArgumentException("reorderPoint must be >= 0");
        }
        if (newReorderQuantity.signum() < 0) {
            throw new IllegalArgumentException("reorderQuantity must be >= 0");
        }
        if (newReorderPoint.compareTo(this.reorderPoint) == 0
            && newReorderQuantity.compareTo(this.reorderQuantity) == 0) return;
        this.reorderPoint = newReorderPoint;
        this.reorderQuantity = newReorderQuantity;
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    public StockItemId id()                  { return id; }
    public UUID productId()                  { return productId; }
    public String productSku()               { return productSku; }
    public String productName()              { return productName; }
    public String productType()              { return productType; }
    public String baseUomCode()              { return baseUomCode; }
    public StockTrackingMode trackingMode()  { return trackingMode; }
    public BigDecimal reorderPoint()         { return reorderPoint; }
    public BigDecimal reorderQuantity()      { return reorderQuantity; }
    public long version()                    { return version; }
}
