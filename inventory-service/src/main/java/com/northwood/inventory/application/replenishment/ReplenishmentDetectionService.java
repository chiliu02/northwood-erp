package com.northwood.inventory.application.replenishment;

import com.northwood.inventory.application.ReorderPolicyLookup;
import com.northwood.inventory.application.ReorderPolicyLookup.ReorderPolicy;
import com.northwood.inventory.application.StockBalanceLookup;
import com.northwood.inventory.application.inbox.ProductReplenishmentProjection;
import com.northwood.inventory.application.inbox.ProductReplenishmentProjection.Replenishment;
import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.ReplenishmentRequest.Reason;
import com.northwood.inventory.domain.ReplenishmentRequest.TargetService;
import com.northwood.inventory.domain.ReplenishmentRequestRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * §2.35 Slice B: the reorder-point monitor.
 *
 * <p>Single entry point: {@link #checkAfterOnHandDecrement(UUID, UUID)}.
 * Called by every application service that decrements
 * {@code stock_balance.on_hand_quantity} (shipment posting, downward stock
 * adjustment) <em>inside the same {@code @Transactional} boundary</em> as the
 * decrement itself. If the post-decrement on-hand has dropped below the SKU's
 * reorder point and no replenishment is already open for the (product,
 * warehouse) pair, the service raises a {@link ReplenishmentRequest} which
 * emits {@code inventory.ReplenishmentRequested} to the outbox.
 *
 * <p>The one-open-per-(product, warehouse) invariant is enforced by a partial
 * unique index — the detection runs check-then-insert without locking, and a
 * concurrent thread winning the insert race produces a
 * {@link DuplicateKeyException} which is caught and logged at DEBUG (the
 * semantic intent of the invariant is "ignore the second trigger while the
 * first is still open"; both threads observe the breach, both attempt to
 * raise, exactly one succeeds).
 *
 * <p>The §2.35 second trigger source ({@code RawMaterialShortageDetected} →
 * inventory bridge, Slice C) does NOT call this method — it builds the
 * request directly with {@code reason = WORK_ORDER_SHORTAGE} and a quantity
 * derived from the shortage, then calls
 * {@link #raiseIfNoneOpen(UUID, UUID, BigDecimal, Reason)} (split off so the
 * Slice-C bridge can supply its own quantity and reason while reusing the
 * routing + invariant-handling).
 */
@Service
public class ReplenishmentDetectionService {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentDetectionService.class);

    private final ReorderPolicyLookup reorderPolicies;
    private final StockBalanceLookup stockBalances;
    private final ProductReplenishmentProjection productReplenishment;
    private final ReplenishmentRequestRepository replenishmentRequests;

    public ReplenishmentDetectionService(
        ReorderPolicyLookup reorderPolicies,
        StockBalanceLookup stockBalances,
        ProductReplenishmentProjection productReplenishment,
        ReplenishmentRequestRepository replenishmentRequests
    ) {
        this.reorderPolicies = reorderPolicies;
        this.stockBalances = stockBalances;
        this.productReplenishment = productReplenishment;
        this.replenishmentRequests = replenishmentRequests;
    }

    /**
     * Trigger A — reorder-point breach. Call after every on-hand decrement,
     * inside the same {@code @Transactional} as the decrement so the
     * {@code ReplenishmentRequested} event lands in the outbox atomically with
     * the balance write.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void checkAfterOnHandDecrement(UUID warehouseId, UUID productId) {
        Optional<ReorderPolicy> policy = reorderPolicies.findByProductId(productId);
        if (policy.isEmpty()) {
            log.debug("no reorder policy for product_id={} (stock_item row missing) — skipping replenishment check",
                productId);
            return;
        }
        BigDecimal reorderPoint = policy.get().reorderPoint();
        BigDecimal reorderQuantity = policy.get().reorderQuantity();
        if (reorderPoint.signum() <= 0) {
            log.debug("reorder_point={} for product_id={} — no automatic replenishment configured",
                reorderPoint, productId);
            return;
        }

        BigDecimal onHand = stockBalances.findBalance(warehouseId, productId)
            .map(b -> b.onHand())
            .orElse(BigDecimal.ZERO);
        if (onHand.compareTo(reorderPoint) >= 0) {
            return;
        }

        if (reorderQuantity == null || reorderQuantity.signum() <= 0) {
            log.warn("on_hand={} < reorder_point={} for product_id={} warehouse_id={} but reorder_quantity={} is non-positive — "
                + "cannot raise a replenishment with zero quantity; operator should set a positive reorder_quantity",
                onHand, reorderPoint, productId, warehouseId, reorderQuantity);
            return;
        }

        raiseIfNoneOpen(productId, warehouseId, reorderQuantity, Reason.REORDER_POINT_BREACH);
    }

    /**
     * Shared inner path used by both triggers: routes via make-vs-buy, applies
     * the one-open invariant, persists. Public for the Slice C bridge to call.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void raiseIfNoneOpen(UUID productId, UUID warehouseId, BigDecimal quantity, Reason reason) {
        Optional<Replenishment> flags = productReplenishment.findByProductId(productId);
        if (flags.isEmpty()) {
            log.warn("no inventory.product_replenishment row for product_id={} — cannot classify make-vs-buy, "
                + "skipping replenishment (reason={}, qty={}, warehouse_id={})",
                productId, reason.dbValue(), quantity, warehouseId);
            return;
        }
        boolean purchased = flags.get().isPurchased();
        boolean manufactured = flags.get().isManufactured();
        if (!purchased && !manufactured) {
            log.warn("unsourceable SKU product_id={} (is_purchased=false, is_manufactured=false) — "
                + "skipping replenishment (reason={}, qty={}, warehouse_id={})",
                productId, reason.dbValue(), quantity, warehouseId);
            return;
        }

        // Prefer manufactured when both flags are true — vertically-integrated
        // SKUs route to the in-house path by default. Operators who prefer the
        // procurement route can flip is_manufactured to false on the product
        // card.
        TargetService target = manufactured ? TargetService.MANUFACTURING : TargetService.PURCHASING;

        ReplenishmentRequest r = ReplenishmentRequest.request(
            productId, warehouseId, quantity, target, reason
        );
        try {
            replenishmentRequests.save(r);
            log.info("raised replenishment_request {} for product_id={} warehouse_id={} qty={} → {} (reason={})",
                r.id().value(), productId, warehouseId, quantity, target.dbValue(), reason.dbValue());
        } catch (DuplicateKeyException e) {
            // Concurrent trigger or earlier-still-open request won the partial
            // unique index. Exactly the invariant we want — log DEBUG and let
            // the existing open request close the gap.
            log.debug("replenishment already open for product_id={} warehouse_id={} — skipping duplicate (reason={})",
                productId, warehouseId, reason.dbValue());
        }
    }

    /**
     * §2.36 Trigger C — sales-order partial-reservation shortage. Called by
     * {@code SalesOrderPurchasingRequestedHandler} per shortage line.
     * Routes via make-vs-buy (same classifier as
     * {@link #raiseIfNoneOpen(UUID, UUID, BigDecimal, Reason)}) but always
     * uses {@code reason = SALES_ORDER_SHORTAGE} and stamps the sales-order
     * line as a back-reference so the eventual {@code ReplenishmentFulfilled}
     * can un-park the originating fulfilment saga.
     *
     * <p>Crucially, SO-shortage requests are EXCLUDED from the one-open-per-
     * (product, warehouse) partial unique index (§2.36 Slice A schema-prep) —
     * multiple sales orders short on the same SKU each get their own request,
     * each back-referenced to a distinct line. No {@code DuplicateKeyException}
     * swallow needed: the schema permits the multiplicity.
     *
     * <p>Unsourceable SKUs (is_purchased=false AND is_manufactured=false) skip
     * with a WARN — the order line is dead-end, the saga will eventually time
     * out at {@code purchasing_requested} until operator intervention.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void raiseForSalesOrderShortage(
        UUID productId,
        UUID warehouseId,
        BigDecimal quantity,
        UUID sourceSalesOrderHeaderId,
        UUID sourceSalesOrderLineId
    ) {
        Optional<Replenishment> flags = productReplenishment.findByProductId(productId);
        if (flags.isEmpty()) {
            log.warn("no inventory.product_replenishment row for product_id={} — cannot classify make-vs-buy, "
                + "skipping sales-order-shortage replenishment (qty={}, warehouse_id={}, sales_order={}, sales_order_line={})",
                productId, quantity, warehouseId, sourceSalesOrderHeaderId, sourceSalesOrderLineId);
            return;
        }
        boolean purchased = flags.get().isPurchased();
        boolean manufactured = flags.get().isManufactured();
        if (!purchased && !manufactured) {
            log.warn("unsourceable SKU product_id={} (is_purchased=false, is_manufactured=false) — "
                + "skipping sales-order-shortage replenishment (qty={}, warehouse_id={}, sales_order={}, sales_order_line={})",
                productId, quantity, warehouseId, sourceSalesOrderHeaderId, sourceSalesOrderLineId);
            return;
        }

        // Same make-vs-buy preference as the proactive paths: vertically-
        // integrated SKUs default to manufacturing.
        TargetService target = manufactured ? TargetService.MANUFACTURING : TargetService.PURCHASING;

        ReplenishmentRequest r = ReplenishmentRequest.requestForSalesOrderShortage(
            productId, warehouseId, quantity, target, sourceSalesOrderHeaderId, sourceSalesOrderLineId
        );
        replenishmentRequests.save(r);
        log.info("raised sales-order-shortage replenishment_request {} for product_id={} warehouse_id={} qty={} → {} (sales_order={}, sales_order_line={})",
            r.id().value(), productId, warehouseId, quantity, target.dbValue(),
            sourceSalesOrderHeaderId, sourceSalesOrderLineId);
    }
}
