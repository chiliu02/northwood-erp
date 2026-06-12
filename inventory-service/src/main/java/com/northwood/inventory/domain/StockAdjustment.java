package com.northwood.inventory.domain;

import com.northwood.inventory.domain.events.StockAdjusted;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root for a stock adjustment: a manual inventory gain or loss for a
 * single product (cycle-count correction, damage, shrinkage, demo setup).
 *
 * <p>Post-only, single creation path {@link #post}: it records the change as a
 * positive {@code quantity} magnitude + a {@link StockMovementDirection}
 * ({@code IN} = gain, {@code OUT} = loss) and goes straight to
 * {@code 'posted'}. The signed-delta math (and an absolute "set on-hand to N",
 * resolved to {@code N − current}) happens in {@code StockAdjustmentService}
 * before this factory — the aggregate only ever sees a resolved positive
 * magnitude + direction.
 *
 * <p>Emits {@link StockAdjusted} for finance: the GL entry that values the
 * delta at standard cost. {@code DRAFT}/{@code REVERSED} are schema-prep, not
 * produced by Java today (mirrors {@link GoodsReceipt.Status}).
 */
public final class StockAdjustment {

    /**
     * Wire-format aggregate-type stamped onto
     * {@code inventory.outbox_message.aggregate_type} for events this aggregate emits.
     */
    public static final String AGGREGATE_TYPE = InventoryAggregateTypes.STOCK_ADJUSTMENT;

    /** Adjustment lifecycle status. Mirrors the schema CHECK on {@code inventory.stock_adjustment.status}. */
    public enum Status {
        /** Schema-prep — not currently produced by Java. */
        DRAFT("draft"),
        POSTED("posted"),
        /** Schema-prep — not currently produced by Java. */
        REVERSED("reversed");

        private final String code;

        Status(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Status fromCode(String value) {
            for (Status s : values()) {
                if (s.code.equals(value)) return s;
            }
            throw Assert.unknownValue("stock_adjustment status", value);
        }
    }

    private final StockAdjustmentId id;
    private final String adjustmentNumber;
    private final UUID warehouseId;
    private final String warehouseCode;
    private final UUID productId;
    private final String productSku;
    private final String productName;
    private final StockMovementDirection direction;
    private final BigDecimal quantity;
    private final String reason;
    private final Status status;
    private final long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /** Factory: post an adjustment. {@code quantity} is a positive magnitude; {@code direction} carries the sign. */
    public static StockAdjustment post(
        String adjustmentNumber,
        UUID warehouseId,
        String warehouseCode,
        UUID productId,
        String productSku,
        String productName,
        StockMovementDirection direction,
        BigDecimal quantity,
        String reason
    ) {
        Assert.notBlank(adjustmentNumber, "adjustmentNumber");
        Assert.notNull(warehouseId, "warehouseId");
        Assert.notNull(productId, "productId");
        Assert.notBlank(productSku, "productSku");
        Assert.notBlank(productName, "productName");
        Assert.notNull(direction, "direction");
        Assert.notNull(quantity, "quantity");
        Assert.argument(quantity.signum() > 0, "quantity must be positive");
        Assert.notBlank(reason, "reason");

        StockAdjustmentId id = StockAdjustmentId.newId();
        StockAdjustment adjustment = new StockAdjustment(
            id, adjustmentNumber, warehouseId, warehouseCode,
            productId, productSku, productName,
            direction, quantity, reason, Status.POSTED, 0L
        );
        adjustment.pendingEvents.add(new StockAdjusted(
            UUID.randomUUID(),
            id.value(),
            adjustmentNumber,
            warehouseId,
            warehouseCode,
            productId,
            productSku,
            productName,
            direction.code(),
            quantity,
            reason,
            Instant.now()
        ));
        return adjustment;
    }

    /** Factory: hydrate from the DB; emits no events. */
    public static StockAdjustment reconstitute(
        StockAdjustmentId id, String adjustmentNumber,
        UUID warehouseId, String warehouseCode,
        UUID productId, String productSku, String productName,
        StockMovementDirection direction, BigDecimal quantity, String reason,
        Status status, long version
    ) {
        return new StockAdjustment(
            id, adjustmentNumber, warehouseId, warehouseCode,
            productId, productSku, productName,
            direction, quantity, reason, status, version
        );
    }

    private StockAdjustment(
        StockAdjustmentId id, String adjustmentNumber,
        UUID warehouseId, String warehouseCode,
        UUID productId, String productSku, String productName,
        StockMovementDirection direction, BigDecimal quantity, String reason,
        Status status, long version
    ) {
        this.id = id;
        this.adjustmentNumber = adjustmentNumber;
        this.warehouseId = warehouseId;
        this.warehouseCode = warehouseCode;
        this.productId = productId;
        this.productSku = productSku;
        this.productName = productName;
        this.direction = direction;
        this.quantity = quantity;
        this.reason = reason;
        this.status = status;
        this.version = version;
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    public StockAdjustmentId id()             { return id; }
    public String adjustmentNumber()          { return adjustmentNumber; }
    public UUID warehouseId()                 { return warehouseId; }
    public String warehouseCode()             { return warehouseCode; }
    public UUID productId()                   { return productId; }
    public String productSku()                { return productSku; }
    public String productName()               { return productName; }
    public StockMovementDirection direction() { return direction; }
    public BigDecimal quantity()              { return quantity; }
    public String reason()                    { return reason; }
    public Status status()                    { return status; }
    public long version()                     { return version; }
}
