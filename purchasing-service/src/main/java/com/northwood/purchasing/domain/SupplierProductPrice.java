package com.northwood.purchasing.domain;

import com.northwood.purchasing.domain.events.SupplierProductPriceChanged;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for one row of the supplier price list — the data of record
 * for a single {@code (supplier, product, currency)} tuple's unit price.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@code unitPrice > 0} at every transition (register + updatePrice).</li>
 *   <li>{@code (supplierId, productId, currencyCode)} is the natural key (DB
 *       enforces uniqueness; the aggregate doesn't re-key after registration).</li>
 *   <li>{@link #updatePrice} is a no-op when the new price compares equal
 *       (via {@link BigDecimal#compareTo}) — no version bump, no event.</li>
 * </ul>
 *
 * <p>Promoted from a row-level write port 2026-05-16 (§2.17). Previously the
 * port {@code SupplierProductPriceRepository} carried row-shaped methods
 * ({@code insert}, {@code updatePrice}) and the application service hand-rolled
 * the outbox event — now the aggregate owns the state machine and the repository
 * drains {@code pendingEvents} at {@code save()}.
 */
public final class SupplierProductPrice {

    /**
     * Wire-format aggregate-type stamped onto {@code purchasing.outbox_message.aggregate_type}
     * for events this aggregate emits.
     */
    public static final String AGGREGATE_TYPE = "SupplierProductPrice";

    private final SupplierProductPriceId id;
    private final UUID supplierId;
    private final UUID productId;
    private final String currencyCode;
    private BigDecimal unitPrice;
    private final long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private SupplierProductPrice(
        SupplierProductPriceId id,
        UUID supplierId,
        UUID productId,
        String currencyCode,
        BigDecimal unitPrice,
        long version
    ) {
        this.id = id;
        this.supplierId = supplierId;
        this.productId = productId;
        this.currencyCode = currencyCode;
        this.unitPrice = unitPrice;
        this.version = version;
    }

    /** Mint a new price row. Emits {@link SupplierProductPriceChanged} with {@code oldUnitPrice = null}. */
    public static SupplierProductPrice register(
        UUID supplierId,
        UUID productId,
        String currencyCode,
        BigDecimal unitPrice
    ) {
        Objects.requireNonNull(supplierId, "supplierId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(currencyCode, "currencyCode");
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (currencyCode.isBlank()) {
            throw new IllegalArgumentException("currencyCode must not be blank");
        }
        if (unitPrice.signum() <= 0) {
            throw new IllegalArgumentException("unitPrice must be > 0");
        }
        SupplierProductPriceId id = SupplierProductPriceId.newId();
        SupplierProductPrice price = new SupplierProductPrice(
            id, supplierId, productId, currencyCode, unitPrice, 0L
        );
        price.pendingEvents.add(new SupplierProductPriceChanged(
            UUID.randomUUID(),
            id.value(),
            supplierId,
            productId,
            currencyCode,
            null,
            unitPrice,
            Instant.now()
        ));
        return price;
    }

    /** Reconstitute from a DB row. Emits nothing. */
    public static SupplierProductPrice reconstitute(
        SupplierProductPriceId id,
        UUID supplierId,
        UUID productId,
        String currencyCode,
        BigDecimal unitPrice,
        long version
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(supplierId, "supplierId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(currencyCode, "currencyCode");
        Objects.requireNonNull(unitPrice, "unitPrice");
        return new SupplierProductPrice(id, supplierId, productId, currencyCode, unitPrice, version);
    }

    /**
     * Revise the unit price. No-op (no event, no version bump) when
     * {@code newUnitPrice.compareTo(currentUnitPrice) == 0} so callers don't
     * have to short-circuit themselves.
     */
    public void updatePrice(BigDecimal newUnitPrice) {
        Objects.requireNonNull(newUnitPrice, "newUnitPrice");
        if (newUnitPrice.signum() <= 0) {
            throw new IllegalArgumentException("newUnitPrice must be > 0");
        }
        if (this.unitPrice.compareTo(newUnitPrice) == 0) {
            return;
        }
        BigDecimal oldPrice = this.unitPrice;
        this.unitPrice = newUnitPrice;
        this.pendingEvents.add(new SupplierProductPriceChanged(
            UUID.randomUUID(),
            id.value(),
            supplierId,
            productId,
            currencyCode,
            oldPrice,
            newUnitPrice,
            Instant.now()
        ));
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    public SupplierProductPriceId id()  { return id; }
    public UUID supplierId()            { return supplierId; }
    public UUID productId()             { return productId; }
    public String currencyCode()        { return currencyCode; }
    public BigDecimal unitPrice()       { return unitPrice; }
    public long version()               { return version; }
}
