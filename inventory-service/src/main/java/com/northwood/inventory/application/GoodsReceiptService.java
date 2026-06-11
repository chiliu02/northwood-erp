package com.northwood.inventory.application;

import com.northwood.inventory.application.dto.GoodsReceiptLineRequest;
import com.northwood.inventory.application.dto.GoodsReceiptView;
import com.northwood.inventory.application.dto.PostGoodsReceiptCommand;
import com.northwood.inventory.application.inbox.PurchaseOrderLineFactsProjection;
import com.northwood.inventory.domain.GoodsReceipt;
import com.northwood.inventory.domain.GoodsReceiptId;
import com.northwood.inventory.domain.GoodsReceiptLine;
import com.northwood.inventory.domain.GoodsReceiptRepository;
import com.northwood.inventory.domain.StockMovementDirection;
import com.northwood.inventory.domain.StockMovementSourceTypes;
import com.northwood.inventory.domain.StockMovementType;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.ReplenishmentRequestRepository;
import com.northwood.shared.application.exception.BadRequestException;
import com.northwood.shared.application.exception.ConflictException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for goods receipts. Posts a receipt against a PO,
 * bumps {@code stock_balance.on_hand_quantity} for each line in the same
 * transaction, persists the receipt header + lines, and emits
 * {@code inventory.GoodsReceived} via the outbox. Two cross-context
 * consumers react: purchasing matches the receipt to PO lines (advances P2P
 * saga); manufacturing un-parks any MTO saga blocked on
 * {@code raw_material_shortage} for the received product.
 *
 * <p>Phase 3 simplification: receipts go straight to {@code 'posted'}; the
 * {@code 'draft'} state for staged partial receipts is reserved for a future
 * slice.
 *
 * <p>Public methods return {@link GoodsReceiptView} rather than the
 * {@code GoodsReceipt} aggregate.
 */
@Service
public class GoodsReceiptService {

    /**
     * Thrown when a goods-receipt line names a {@code purchase_order_line_id}
     * that either doesn't appear in {@code purchase_order_line_facts} or maps
     * to a different {@code product_id} than the line claims. Mapped to HTTP
     * 400 by the controller. Defence-in-depth: catches a buggy / malicious
     * client that bypassed the SPA picker.
     */
    public static class GoodsReceiptLineProductMismatchException extends BadRequestException {
        public static final String CODE = "GOODS_RECEIPT_LINE_PRODUCT_MISMATCH";
        private final UUID purchaseOrderLineId;
        private final UUID expectedProductId;
        private final UUID actualProductId;
        public GoodsReceiptLineProductMismatchException(UUID purchaseOrderLineId, UUID expectedProductId, UUID actualProductId) {
            super(CODE, expectedProductId == null
                ? "Unknown purchase_order_line_id=%s (no matching projection row; line may not belong to a created purchase order)".formatted(purchaseOrderLineId)
                : "Product mismatch on purchase_order_line_id=%s: expected product=%s, got=%s".formatted(purchaseOrderLineId, expectedProductId, actualProductId)
            );
            this.purchaseOrderLineId = purchaseOrderLineId;
            this.expectedProductId = expectedProductId;
            this.actualProductId = actualProductId;
        }
        public UUID purchaseOrderLineId() { return purchaseOrderLineId; }
        public UUID expectedProductId() { return expectedProductId; }
        public UUID actualProductId() { return actualProductId; }
        @Override public Map<String, Object> params() {
            Map<String, Object> p = new HashMap<>();
            p.put("purchaseOrderLineId", purchaseOrderLineId);
            if (expectedProductId != null) p.put("expectedProductId", expectedProductId);
            if (actualProductId != null) p.put("actualProductId", actualProductId);
            return Map.copyOf(p);
        }
    }

    /**
     * Thrown when a goods receipt for a PO linked to an {@code order_pegged}
     * ({@code to_order}) replenishment receives <em>more</em> than the pegged
     * quantity. A to-order line raises dedicated, order-pegged supply for the
     * exact line quantity, and its receipt is reserved straight to the
     * originating sales-order line (the atomic receipt+reserve peg below). The
     * excess beyond the peg would instead land in free ATP where no order can
     * reserve it — every to-order line raises its own supply and never draws
     * from the shared pool — orphaning as dead inventory. This is the
     * receipt-side twin of {@code purchasing}'s manual-requisition guard
     * ({@code ToOrderProductManualPurchaseException}). Mapped to HTTP 409.
     */
    public static class ToOrderOverReceiptException extends ConflictException {
        public static final String CODE = "GOODS_RECEIPT_TO_ORDER_OVER_RECEIPT";
        private final String productSku;
        private final BigDecimal peggedQuantity;
        private final BigDecimal receivedQuantity;
        public ToOrderOverReceiptException(String productSku, BigDecimal peggedQuantity, BigDecimal receivedQuantity) {
            super(CODE, "Over-receipt on to-order product sku=" + productSku + ": received "
                + receivedQuantity + " against an order-pegged quantity of " + peggedQuantity
                + ". A to-order product raises dedicated supply for the exact line quantity and its "
                + "receipt is reserved straight to the originating sales-order line; the excess would "
                + "land in free stock where no order can reserve it (every to-order line raises its own "
                + "supply), orphaning as dead inventory. Receive only the pegged quantity.");
            this.productSku = productSku;
            this.peggedQuantity = peggedQuantity;
            this.receivedQuantity = receivedQuantity;
        }
        public String productSku() { return productSku; }
        public BigDecimal peggedQuantity() { return peggedQuantity; }
        public BigDecimal receivedQuantity() { return receivedQuantity; }
        @Override public Map<String, Object> params() {
            return Map.of("productSku", productSku, "peggedQuantity", peggedQuantity, "receivedQuantity", receivedQuantity);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(GoodsReceiptService.class);

    private final GoodsReceiptRepository goodsReceipts;
    private final StockBalanceWriter stockBalances;
    private final StockMovementWriter movements;
    private final WarehouseLookup warehouses;
    private final PurchaseOrderLineFactsProjection purchaseOrderLineFacts;
    private final ReplenishmentRequestRepository replenishmentRequests;

    public GoodsReceiptService(
        GoodsReceiptRepository goodsReceipts,
        StockBalanceWriter stockBalances,
        StockMovementWriter movements,
        WarehouseLookup warehouses,
        PurchaseOrderLineFactsProjection purchaseOrderLineFacts,
        ReplenishmentRequestRepository replenishmentRequests
    ) {
        this.goodsReceipts = goodsReceipts;
        this.stockBalances = stockBalances;
        this.movements = movements;
        this.warehouses = warehouses;
        this.purchaseOrderLineFacts = purchaseOrderLineFacts;
        this.replenishmentRequests = replenishmentRequests;
    }

    @Transactional(readOnly = true)
    public Optional<GoodsReceiptView> findById(UUID id) {
        return goodsReceipts.findById(GoodsReceiptId.of(id)).map(GoodsReceiptView::from);
    }

    @Transactional(readOnly = true)
    public List<GoodsReceiptView> findAllHeaders() {
        return goodsReceipts.findAllHeaders().stream().map(GoodsReceiptView::from).toList();
    }

    @Transactional
    public GoodsReceiptView post(PostGoodsReceiptCommand command) {
        String warehouseCode = command.warehouseCode() == null ? WarehouseCodes.MAIN : command.warehouseCode();
        UUID warehouseId = warehouses.findIdByCode(warehouseCode);

        // Defence-in-depth: reject if any line's productId doesn't match the
        // projected purchase_order_line_facts for the named purchase_order_line_id.
        // Lines without a purchase_order_line_id (unlinked manual receipts) skip
        // the check — that's an existing API affordance.
        for (GoodsReceiptLineRequest line : command.lines()) {
            if (line.purchaseOrderLineId() == null) {
                continue;
            }
            Optional<UUID> expected = purchaseOrderLineFacts.findProductIdForLine(line.purchaseOrderLineId());
            if (expected.isEmpty() || !expected.get().equals(line.productId())) {
                throw new GoodsReceiptLineProductMismatchException(
                    line.purchaseOrderLineId(), expected.orElse(null), line.productId()
                );
            }
        }

        // Resolve a linked, still-open replenishment once — used by both the
        // to-order over-receipt guard (below, before any stock mutation) and the
        // peg/fulfil step further down. Linked at PurchaseOrderCreated time by
        // PurchaseOrderCreatedHandler.
        Optional<ReplenishmentRequest> linkedReplenishment = command.purchaseOrderHeaderId() == null
            ? Optional.empty()
            : replenishmentRequests.findByLinkedPurchaseOrderId(command.purchaseOrderHeaderId())
                .filter(r -> r.status() == ReplenishmentRequest.Status.DISPATCHED);

        // To-order over-receipt guard. An order-pegged replenishment is raised
        // for the exact line quantity and its receipt is reserved straight to the
        // SO line; receiving MORE than the peg would credit the excess into free
        // ATP where no order can reserve it (every to-order line raises its own
        // supply), orphaning it as dead stock. Fail fast — before the on_hand
        // credit — so a rejected over-receipt leaves no trace.
        linkedReplenishment
            .filter(r -> r.reason() == ReplenishmentRequest.Reason.ORDER_PEGGED)
            .ifPresent(r -> {
                BigDecimal receivedForProduct = command.lines().stream()
                    .filter(l -> l.productId().equals(r.productId()))
                    .map(GoodsReceiptLineRequest::receivedQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (receivedForProduct.compareTo(r.requestedQuantity()) > 0) {
                    String sku = command.lines().stream()
                        .filter(l -> l.productId().equals(r.productId()))
                        .map(GoodsReceiptLineRequest::productSku)
                        .findFirst()
                        .orElse(r.productId().toString());
                    throw new ToOrderOverReceiptException(sku, r.requestedQuantity(), receivedForProduct);
                }
            });

        List<GoodsReceiptLine> lines = new ArrayList<>();
        for (GoodsReceiptLineRequest line : command.lines()) {
            BigDecimal unitCost = line.unitCost() == null ? BigDecimal.ZERO : line.unitCost();
            BigDecimal lineCost = line.receivedQuantity().multiply(unitCost);
            lines.add(new GoodsReceiptLine(
                UUID.randomUUID(),
                line.purchaseOrderLineId(),
                line.productId(),
                line.productSku(),
                line.productName(),
                line.receivedQuantity(),
                unitCost,
                lineCost
            ));
        }

        GoodsReceipt receipt = GoodsReceipt.post(
            command.goodsReceiptNumber(),
            command.purchaseOrderHeaderId(),
            command.purchaseOrderNumber(),
            command.supplierId(),
            command.supplierName(),
            warehouseId,
            warehouseCode,
            lines
        );
        goodsReceipts.save(receipt);

        for (GoodsReceiptLine l : lines) {
            stockBalances.bump(warehouseId, l.productId(), l.receivedQuantity());
            movements.record(
                warehouseId, l.productId(), l.productSku(), l.productName(),
                StockMovementType.PURCHASE_RECEIPT, StockMovementDirection.IN,
                l.receivedQuantity(), l.unitCost(),
                StockMovementSourceTypes.GOODS_RECEIPT, receipt.id().value(), l.id()
            );
        }

        // If this receipt is for a PO linked to a replenishment, fulfil the
        // replenishment (emits inventory.ReplenishmentFulfilled). Resolved once
        // above (linkedReplenishment).
        linkedReplenishment
                .ifPresent(r -> {
                    // Buy-to-order atomic peg — symmetric to the
                    // make-to-order WO-completion peg. The received goods for an
                    // order-pegged request are dedicated to the originating SO
                    // line, so reserve them in THIS transaction (right after the
                    // on_hand credit above) so they never enter free ATP and
                    // can't be stolen. markFulfilled then emits
                    // ReplenishmentFulfilled(pegged=true) → sales ships off the
                    // peg without a re-reservation retry. Releasing this peg on
                    // cancel is the un-peg job.
                    if (r.reason() == ReplenishmentRequest.Reason.ORDER_PEGGED) {
                        BigDecimal receivedForProduct = lines.stream()
                            .filter(l -> l.productId().equals(r.productId()))
                            .map(GoodsReceiptLine::receivedQuantity)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                        if (receivedForProduct.signum() > 0) {
                            boolean pegged = stockBalances.tryReserveOnHand(
                                warehouseId, r.productId(), receivedForProduct);
                            if (pegged) {
                                log.info("pegged {} of product={} to sales_order={} sales_order_line={} (atomic receipt+reserve)",
                                    receivedForProduct, r.productId(),
                                    r.sourceSalesOrderHeaderId(), r.sourceSalesOrderLineId());
                            } else {
                                log.warn("could not peg-reserve {} of product={} for sales_order={} — free stock insufficient "
                                    + "immediately after receipt (concurrent reservation?); SO line falls back to the retry path",
                                    receivedForProduct, r.productId(), r.sourceSalesOrderHeaderId());
                            }
                        }
                    }
                    r.markFulfilled();
                    replenishmentRequests.save(r);
                    log.info("fulfilled replenishment_request={} via goods_receipt for purchase_order={}",
                        r.id().value(), command.purchaseOrderHeaderId());
                });

        log.info("posted goods_receipt {} for purchase_order={} ({} line(s)) at warehouse={}",
            receipt.goodsReceiptNumber(), command.purchaseOrderHeaderId(),
            lines.size(), warehouseCode);
        return GoodsReceiptView.from(receipt);
    }

}
