package com.northwood.product.domain;

import com.northwood.shared.domain.Money;
import com.northwood.shared.domain.Sku;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.northwood.product.domain.events.ApprovedVendorListChanged;
import com.northwood.product.domain.events.BomActivated;
import com.northwood.product.domain.events.MakeVsBuyChanged;
import com.northwood.product.domain.events.ProductCreated;
import com.northwood.product.domain.events.ProductDiscontinued;
import com.northwood.product.domain.events.SalesPriceChanged;
import com.northwood.product.domain.events.StandardCostChanged;
import com.northwood.product.domain.events.ReorderPolicyChanged;
import com.northwood.product.domain.events.ValuationClassChanged;
import com.northwood.shared.domain.DomainEvent;

/**
 * Product master aggregate root. Every other context references a product by
 * {@link ProductId}; the canonical SKU, name, type, base UoM, pricing, and
 * reorder policy live here. Reorder policy is owned here as the data of record
 * (Shape A / Material Master pattern); inventory keeps its own per-SKU columns
 * as a projection updated from {@link ReorderPolicyChanged} events.
 *
 * <p>Mutations are intent-named methods that emit domain events captured by
 * the application service for outbox publication.
 */
public class Product {

    /**
     * Wire-format aggregate-type stamped onto {@code product.outbox_message.aggregate_type}
     * for events this aggregate emits.
     */
    public static final String AGGREGATE_TYPE = "Product";

    private final ProductId id;
    private final Sku sku;
    private String name;
    private String description;
    private final ProductType productType;
    private final UUID baseUomId;
    private boolean stocked;
    private boolean purchased;
    private boolean manufactured;
    private boolean sellable;
    private Money salesPrice;
    private Money standardCost;
    private BigDecimal reorderPoint;
    private BigDecimal reorderQuantity;
    private String valuationClass;
    private UUID activeBomId;
    private Status status;
    private final long version;

    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    public enum Status { ACTIVE, INACTIVE, DISCONTINUED }

    /** Factory: register a brand-new product. Emits ProductCreated. */
    public static Product register(
        Sku sku,
        String name,
        String description,
        ProductType productType,
        UUID baseUomId,
        Money salesPrice,
        Money standardCost
    ) {
        Product p = new Product(
            ProductId.newId(),
            Objects.requireNonNull(sku),
            Objects.requireNonNull(name),
            description,
            Objects.requireNonNull(productType),
            Objects.requireNonNull(baseUomId),
            // sensible defaults; intent-named setters below for explicit changes
            false, false, false, false,
            Objects.requireNonNull(salesPrice),
            Objects.requireNonNull(standardCost),
            // Reorder defaults to 0/0; the planning steward sets it via
            // setReorderPolicy once the SKU is configured.
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            // Shape A facets default to null/unknown; the appropriate steward
            // sets each via the dedicated REST endpoint.
            null, null,
            Status.ACTIVE,
            0L
        );
        p.pendingEvents.add(new ProductCreated(
            UUID.randomUUID(),
            p.id.value(),
            sku.value(),
            name,
            productType.dbValue(),
            Instant.now()
        ));
        return p;
    }

    /** Reconstitute from persistence; does NOT emit events. */
    public static Product reconstitute(
        ProductId id, Sku sku, String name, String description,
        ProductType productType, UUID baseUomId,
        boolean stocked, boolean purchased, boolean manufactured, boolean sellable,
        Money salesPrice, Money standardCost,
        BigDecimal reorderPoint, BigDecimal reorderQuantity,
        String valuationClass, UUID activeBomId,
        Status status, long version
    ) {
        return new Product(id, sku, name, description, productType, baseUomId,
            stocked, purchased, manufactured, sellable,
            salesPrice, standardCost,
            reorderPoint, reorderQuantity,
            valuationClass, activeBomId,
            status, version);
    }

    private Product(
        ProductId id, Sku sku, String name, String description,
        ProductType productType, UUID baseUomId,
        boolean stocked, boolean purchased, boolean manufactured, boolean sellable,
        Money salesPrice, Money standardCost,
        BigDecimal reorderPoint, BigDecimal reorderQuantity,
        String valuationClass, UUID activeBomId,
        Status status, long version
    ) {
        this.id = id;
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.productType = productType;
        this.baseUomId = baseUomId;
        this.stocked = stocked;
        this.purchased = purchased;
        this.manufactured = manufactured;
        this.sellable = sellable;
        this.salesPrice = salesPrice;
        this.standardCost = standardCost;
        this.reorderPoint = reorderPoint;
        this.reorderQuantity = reorderQuantity;
        this.valuationClass = valuationClass;
        this.activeBomId = activeBomId;
        this.status = status;
        this.version = version;
    }

    public void changeSalesPrice(Money newSalesPrice) {
        if (status == Status.DISCONTINUED) {
            throw new IllegalStateException("Cannot change sales price on a discontinued product");
        }
        Objects.requireNonNull(newSalesPrice, "newSalesPrice");
        if (newSalesPrice.equalsByValue(this.salesPrice)) return;
        Money oldSalesPrice = this.salesPrice;
        this.salesPrice = newSalesPrice;
        pendingEvents.add(new SalesPriceChanged(
            UUID.randomUUID(),
            id.value(),
            oldSalesPrice.amount(),
            newSalesPrice.amount(),
            newSalesPrice.currencyCode(),
            Instant.now()
        ));
    }

    public void changeStandardCost(Money newStandardCost) {
        if (status == Status.DISCONTINUED) {
            throw new IllegalStateException("Cannot change standard cost on a discontinued product");
        }
        Objects.requireNonNull(newStandardCost, "newStandardCost");
        if (newStandardCost.equalsByValue(this.standardCost)) return;
        Money oldStandardCost = this.standardCost;
        this.standardCost = newStandardCost;
        pendingEvents.add(new StandardCostChanged(
            UUID.randomUUID(),
            id.value(),
            oldStandardCost.amount(),
            newStandardCost.amount(),
            newStandardCost.currencyCode(),
            Instant.now()
        ));
    }

    /**
     * Set the make-vs-buy classification. At least one of {@code purchased} or
     * {@code manufactured} must be true — a SKU that's neither makeable nor
     * buyable is unsourceable. Discontinued products reject the change.
     * Emits {@link MakeVsBuyChanged} with old + new flags.
     */
    public void changeMakeVsBuy(boolean newIsPurchased, boolean newIsManufactured) {
        if (status == Status.DISCONTINUED) {
            throw new IllegalStateException("Cannot change make-vs-buy on a discontinued product");
        }
        if (!newIsPurchased && !newIsManufactured) {
            throw new IllegalArgumentException(
                "At least one of (purchased, manufactured) must be true; otherwise the SKU is unsourceable"
            );
        }
        if (this.purchased == newIsPurchased && this.manufactured == newIsManufactured) return;
        boolean oldPurchased = this.purchased;
        boolean oldManufactured = this.manufactured;
        this.purchased = newIsPurchased;
        this.manufactured = newIsManufactured;
        pendingEvents.add(new MakeVsBuyChanged(
            UUID.randomUUID(),
            id.value(),
            oldPurchased, newIsPurchased,
            oldManufactured, newIsManufactured,
            Instant.now()
        ));
    }

    public void changeReorderPolicy(BigDecimal newReorderPoint, BigDecimal newReorderQuantity) {
        if (status == Status.DISCONTINUED) {
            throw new IllegalStateException("Cannot change reorder policy on a discontinued product");
        }
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
        BigDecimal oldPoint = this.reorderPoint;
        BigDecimal oldQuantity = this.reorderQuantity;
        this.reorderPoint = newReorderPoint;
        this.reorderQuantity = newReorderQuantity;
        pendingEvents.add(new ReorderPolicyChanged(
            UUID.randomUUID(),
            id.value(),
            oldPoint,
            newReorderPoint,
            oldQuantity,
            newReorderQuantity,
            Instant.now()
        ));
    }

    /**
     * Set the valuation class — drives finance's GL account selection.
     * Discontinued products reject the change. Emits
     * {@link ValuationClassChanged} with old + new.
     */
    public void changeValuationClass(String newValuationClass) {
        if (status == Status.DISCONTINUED) {
            throw new IllegalStateException("Cannot change valuation class on a discontinued product");
        }
        Objects.requireNonNull(newValuationClass, "valuationClass");
        if (newValuationClass.isBlank()) {
            throw new IllegalArgumentException("valuationClass must not be blank");
        }
        if (newValuationClass.equals(this.valuationClass)) return;
        String oldClass = this.valuationClass;
        this.valuationClass = newValuationClass;
        pendingEvents.add(new ValuationClassChanged(
            UUID.randomUUID(),
            id.value(),
            oldClass,
            newValuationClass,
            Instant.now()
        ));
    }

    /**
     * Set the active BOM for this SKU. {@code newBomHeaderId} can be null to
     * indicate "no active BOM" (e.g. SKU is not currently buildable).
     * Emits {@link BomActivated} with old + new.
     */
    public void activateBom(UUID newBomHeaderId) {
        if (status == Status.DISCONTINUED) {
            throw new IllegalStateException("Cannot change active BOM on a discontinued product");
        }
        if (Objects.equals(newBomHeaderId, this.activeBomId)) return;
        UUID oldBomId = this.activeBomId;
        this.activeBomId = newBomHeaderId;
        pendingEvents.add(new BomActivated(
            UUID.randomUUID(),
            id.value(),
            oldBomId,
            newBomHeaderId,
            Instant.now()
        ));
    }

    /**
     * Replace the approved-vendor list. Carries the new list in full;
     * downstream consumers replace their projection in one statement.
     * The actual list of approved vendors is persisted in
     * {@code product.approved_vendor} (separate from the aggregate's
     * core fields) — this method's responsibility is just emitting the
     * event; the application service handles persistence.
     */
    public void emitApprovedVendorListChanged(List<ApprovedVendor> approvedVendors) {
        if (status == Status.DISCONTINUED) {
            throw new IllegalStateException("Cannot change approved vendors on a discontinued product");
        }
        Objects.requireNonNull(approvedVendors, "approvedVendors");
        pendingEvents.add(new ApprovedVendorListChanged(
            UUID.randomUUID(),
            id.value(),
            List.copyOf(approvedVendors),
            Instant.now()
        ));
    }

    public void discontinue() {
        if (status == Status.DISCONTINUED) return;
        this.status = Status.DISCONTINUED;
        pendingEvents.add(new ProductDiscontinued(
            UUID.randomUUID(),
            id.value(),
            Instant.now()
        ));
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    public ProductId id()                  { return id; }
    public Sku sku()                       { return sku; }
    public String name()                   { return name; }
    public String description()            { return description; }
    public ProductType productType()       { return productType; }
    public UUID baseUomId()                { return baseUomId; }
    public boolean isStocked()             { return stocked; }
    public boolean isPurchased()           { return purchased; }
    public boolean isManufactured()        { return manufactured; }
    public boolean isSellable()            { return sellable; }
    public Money salesPrice()              { return salesPrice; }
    public Money standardCost()            { return standardCost; }
    public BigDecimal reorderPoint()       { return reorderPoint; }
    public BigDecimal reorderQuantity()    { return reorderQuantity; }
    public String valuationClass()         { return valuationClass; }
    public UUID activeBomId()              { return activeBomId; }
    public Status status()                 { return status; }
    public long version()                  { return version; }
}
