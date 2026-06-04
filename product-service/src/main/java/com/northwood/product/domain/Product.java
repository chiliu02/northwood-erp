package com.northwood.product.domain;

import com.northwood.shared.domain.Assert;
import com.northwood.shared.domain.Money;
import com.northwood.shared.domain.Sku;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.northwood.product.domain.events.ApprovedVendorListChanged;
import com.northwood.product.domain.events.ActiveBomChanged;
import com.northwood.product.domain.events.MakeVsBuyChanged;
import com.northwood.product.domain.events.PlanningTimeFenceChanged;
import com.northwood.product.domain.events.ProductCreated;
import com.northwood.product.domain.events.ProductDiscontinued;
import com.northwood.product.domain.events.ReplenishmentStrategyChanged;
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
     * Product lifecycle status. Mirrors the schema CHECK on
     * {@code product.product.status}; carries its wire-format string via
     * {@link #dbValue()} (same shape as {@link ProductType} /
     * {@code Customer.Status}).
     */
    public enum Status {
        ACTIVE("active"),
        INACTIVE("inactive"),
        DISCONTINUED("discontinued");

        private final String dbValue;

        Status(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static Status fromDb(String value) {
            for (Status s : values()) {
                if (s.dbValue.equals(value)) return s;
            }
            throw Assert.unknownValue("product status", value);
        }
    }

    /**
     * Wire-format aggregate-type stamped onto {@code product.outbox_message.aggregate_type}
     * for events this aggregate emits.
     */
    public static final String AGGREGATE_TYPE = ProductAggregateTypes.PRODUCT;

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
    private ReplenishmentStrategy replenishmentStrategy;
    private ValuationClass valuationClass;
    private UUID activeBomId;
    /**
     * Planning time fence, in days. The fulfilment saga reads this (via sales's
     * {@code product_card} projection) to defer a far-future order's stock
     * reservation until {@code need-by − fence}. 0 = no fence = reserve
     * immediately (today's behaviour). Non-negative; set via
     * {@link #changePlanningTimeFence}.
     */
    private int planningTimeFenceDays;
    private Status status;
    private final long version;

    /**
     * Approved-vendor list (denormalised into {@code product.approved_vendor} on
     * the persistence side, but logically a child collection of this aggregate).
     * Mutated via {@link #setApprovedVendors}; the repository writes the side
     * table when {@link #pullApprovedVendorsDirty()} returns {@code true}.
     */
    private List<ApprovedVendor> approvedVendors = List.of();
    private boolean approvedVendorsDirty = false;

    private final List<DomainEvent> pendingEvents = new ArrayList<>();

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
            Assert.notNull(sku, "sku"),
            Assert.notBlank(name, "name"),
            description,
            Assert.notNull(productType, "productType"),
            Assert.notNull(baseUomId, "baseUomId"),
            // sensible defaults; intent-named setters below for explicit changes
            false, false, false, false,
            Assert.notNull(salesPrice, "salesPrice"),
            Assert.notNull(standardCost, "standardCost"),
            // Reorder defaults to 0/0; the planning steward sets it via
            // setReorderPolicy once the SKU is configured.
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            // Replenishment strategy defaults to to_stock for anything stocked
            // or produced (REQ-INV-090); services carry no strategy (the axis
            // is N/A). The planning steward flips it to to_order via
            // changeReplenishmentStrategy once the SKU is configured order-pegged.
            productType == ProductType.SERVICE ? null : ReplenishmentStrategy.TO_STOCK,
            // Shape A facets default to null/unknown; the appropriate steward
            // sets each via the dedicated REST endpoint.
            null, null,
            // Planning time fence defaults to 0 (no fence — reserve immediately);
            // the planning steward sets it via changePlanningTimeFence.
            0,
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
        ReplenishmentStrategy replenishmentStrategy,
        ValuationClass valuationClass, UUID activeBomId,
        int planningTimeFenceDays,
        Status status, long version,
        List<ApprovedVendor> approvedVendors
    ) {
        Product p = new Product(id, sku, name, description, productType, baseUomId,
            stocked, purchased, manufactured, sellable,
            salesPrice, standardCost,
            reorderPoint, reorderQuantity,
            replenishmentStrategy, valuationClass, activeBomId,
            planningTimeFenceDays,
            status, version);
        p.approvedVendors = List.copyOf(approvedVendors);
        return p;
    }

    private Product(
        ProductId id, Sku sku, String name, String description,
        ProductType productType, UUID baseUomId,
        boolean stocked, boolean purchased, boolean manufactured, boolean sellable,
        Money salesPrice, Money standardCost,
        BigDecimal reorderPoint, BigDecimal reorderQuantity,
        ReplenishmentStrategy replenishmentStrategy,
        ValuationClass valuationClass, UUID activeBomId,
        int planningTimeFenceDays,
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
        this.replenishmentStrategy = replenishmentStrategy;
        this.valuationClass = valuationClass;
        this.activeBomId = activeBomId;
        this.planningTimeFenceDays = planningTimeFenceDays;
        this.status = status;
        this.version = version;
    }

    public void changeSalesPrice(Money newSalesPrice) {
        Assert.state(status != Status.DISCONTINUED, "Cannot change sales price on a discontinued product");
        Assert.notNull(newSalesPrice, "newSalesPrice");
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
        Assert.state(status != Status.DISCONTINUED, "Cannot change standard cost on a discontinued product");
        Assert.notNull(newStandardCost, "newStandardCost");
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
        Assert.state(status != Status.DISCONTINUED, "Cannot change make-vs-buy on a discontinued product");
        Assert.argument(newIsPurchased || newIsManufactured, "At least one of (purchased, manufactured) must be true; otherwise the SKU is unsourceable");
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
        Assert.state(status != Status.DISCONTINUED, "Cannot change reorder policy on a discontinued product");
        Assert.notNull(newReorderPoint, "reorderPoint");
        Assert.notNull(newReorderQuantity, "reorderQuantity");
        Assert.argument(newReorderPoint.signum() >= 0, "reorderPoint must be >= 0");
        Assert.argument(newReorderQuantity.signum() >= 0, "reorderQuantity must be >= 0");
        // Cross-mutator invariant (REQ-PROD-022): a to_order product's demand is
        // the sales order, so it carries no independent reorder loop. Keep the
        // strategy and the policy from contradicting from this direction too.
        Assert.argument(
            replenishmentStrategy != ReplenishmentStrategy.TO_ORDER
                || (newReorderPoint.signum() == 0 && newReorderQuantity.signum() == 0),
            "Cannot set a non-zero reorder policy on a to_order product (demand is the sales order)");
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
     * Set the replenishment strategy — {@code to_stock} (reorder-point driven)
     * vs {@code to_order} (order-pegged). Orthogonal to make-vs-buy; the two
     * axes combine into the four operator modes. Discontinued products reject
     * the change. Emits {@link ReplenishmentStrategyChanged} with old + new
     * wire-format values.
     *
     * <p>Enforces the REQ-PROD-022 invariant set (these live on the aggregate,
     * not one setter, because they span the strategy / sellable / reorder-policy
     * fields):
     * <ul>
     *   <li>{@code product_type = service ⇒ strategy IS NULL} — services aren't
     *       stocked or produced, so the axis is N/A.</li>
     *   <li>{@code to_order ⇒ is_sellable} — the peg target is a sales-order
     *       line, and only sellable items get SO lines.</li>
     *   <li>{@code to_order ⇒ reorder_point = 0 ∧ reorder_quantity = 0} — a
     *       to_order item has no independent reorder loop.</li>
     * </ul>
     * The schema CHECKs on {@code product.product} mirror the same set.
     */
    public void changeReplenishmentStrategy(ReplenishmentStrategy newStrategy) {
        Assert.state(status != Status.DISCONTINUED, "Cannot change replenishment strategy on a discontinued product");
        if (productType == ProductType.SERVICE) {
            Assert.argument(newStrategy == null, "A service product must have no replenishment strategy");
        } else {
            Assert.notNull(newStrategy, "replenishmentStrategy");
        }
        if (newStrategy == ReplenishmentStrategy.TO_ORDER) {
            Assert.argument(sellable, "A to_order product must be sellable (the peg target is a sales-order line)");
            Assert.argument(reorderPoint.signum() == 0 && reorderQuantity.signum() == 0,
                "A to_order product must have zero reorder point and quantity (demand is the sales order)");
        }
        if (newStrategy == this.replenishmentStrategy) return;
        ReplenishmentStrategy oldStrategy = this.replenishmentStrategy;
        this.replenishmentStrategy = newStrategy;
        pendingEvents.add(new ReplenishmentStrategyChanged(
            UUID.randomUUID(),
            id.value(),
            oldStrategy == null ? null : oldStrategy.dbValue(),
            newStrategy == null ? null : newStrategy.dbValue(),
            Instant.now()
        ));
    }

    /**
     * Set the valuation class — drives finance's GL account selection.
     * Discontinued products reject the change. Emits
     * {@link ValuationClassChanged} with old + new wire-format values
     * (typed enum on the aggregate, {@code dbValue()} on the wire).
     */
    public void changeValuationClass(ValuationClass newValuationClass) {
        Assert.state(status != Status.DISCONTINUED, "Cannot change valuation class on a discontinued product");
        Assert.notNull(newValuationClass, "valuationClass");
        if (newValuationClass == this.valuationClass) return;
        ValuationClass oldClass = this.valuationClass;
        this.valuationClass = newValuationClass;
        pendingEvents.add(new ValuationClassChanged(
            UUID.randomUUID(),
            id.value(),
            oldClass == null ? null : oldClass.dbValue(),
            newValuationClass.dbValue(),
            Instant.now()
        ));
    }

    /**
     * Set the active BOM for this SKU. {@code newBomHeaderId} can be null to
     * indicate "no active BOM" (e.g. SKU is not currently buildable).
     * Emits {@link ActiveBomChanged} with old + new.
     */
    public void activateBom(UUID newBomHeaderId) {
        Assert.state(status != Status.DISCONTINUED, "Cannot change active BOM on a discontinued product");
        if (Objects.equals(newBomHeaderId, this.activeBomId)) return;
        UUID oldBomId = this.activeBomId;
        this.activeBomId = newBomHeaderId;
        pendingEvents.add(new ActiveBomChanged(
            UUID.randomUUID(),
            id.value(),
            oldBomId,
            newBomHeaderId,
            Instant.now()
        ));
    }

    /**
     * Set the planning time fence, in days. The fulfilment saga defers a
     * far-future order's stock reservation until {@code need-by − fence}; 0
     * means no fence (reserve immediately). Must be non-negative. Discontinued
     * products reject the change. Emits {@link PlanningTimeFenceChanged} with
     * old + new.
     */
    public void changePlanningTimeFence(int newPlanningTimeFenceDays) {
        Assert.state(status != Status.DISCONTINUED, "Cannot change planning time fence on a discontinued product");
        Assert.argument(newPlanningTimeFenceDays >= 0, "planningTimeFenceDays must be >= 0");
        if (newPlanningTimeFenceDays == this.planningTimeFenceDays) return;
        int oldDays = this.planningTimeFenceDays;
        this.planningTimeFenceDays = newPlanningTimeFenceDays;
        pendingEvents.add(new PlanningTimeFenceChanged(
            UUID.randomUUID(),
            id.value(),
            oldDays,
            newPlanningTimeFenceDays,
            Instant.now()
        ));
    }

    /**
     * Replace the approved-vendor list. Carries the new list in full;
     * downstream consumers replace their projection in one statement.
     *
     * <p>The list is denormalised into {@code product.approved_vendor} on the
     * persistence side; the repository writes the side table on
     * {@link #pullApprovedVendorsDirty()}. No-op suppression compares as a set
     * (order doesn't matter) so re-saving the same set is a true no-op.
     *
     * <p>Promoted from a row-level write port 2026-05-16. Previously
     * the application service hand-rolled the no-op check + persistence via
     * {@code ApprovedVendorRepository}; now the aggregate owns both.
     */
    public void setApprovedVendors(List<ApprovedVendor> newApprovedVendors) {
        Assert.state(status != Status.DISCONTINUED, "Cannot change approved vendors on a discontinued product");
        Assert.notNull(newApprovedVendors, "approvedVendors");
        if (sameVendorSet(this.approvedVendors, newApprovedVendors)) {
            return;
        }
        this.approvedVendors = List.copyOf(newApprovedVendors);
        this.approvedVendorsDirty = true;
        pendingEvents.add(new ApprovedVendorListChanged(
            UUID.randomUUID(),
            id.value(),
            this.approvedVendors,
            Instant.now()
        ));
    }

    private static boolean sameVendorSet(List<ApprovedVendor> a, List<ApprovedVendor> b) {
        if (a.size() != b.size()) return false;
        return new java.util.HashSet<>(a).equals(new java.util.HashSet<>(b));
    }

    /**
     * One-shot dirty-flag pull: returns {@code true} iff
     * {@link #setApprovedVendors} mutated the list since the last pull, and
     * resets the flag. Called by the repository inside {@code save()} to
     * decide whether to rewrite the {@code product.approved_vendor} rows.
     */
    public boolean pullApprovedVendorsDirty() {
        boolean wasDirty = this.approvedVendorsDirty;
        this.approvedVendorsDirty = false;
        return wasDirty;
    }

    public List<ApprovedVendor> approvedVendors() {
        return approvedVendors;
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
    public ReplenishmentStrategy replenishmentStrategy() { return replenishmentStrategy; }
    public ValuationClass valuationClass() { return valuationClass; }
    public UUID activeBomId()              { return activeBomId; }
    public int planningTimeFenceDays()     { return planningTimeFenceDays; }
    public Status status()                 { return status; }
    public long version()                  { return version; }
}
