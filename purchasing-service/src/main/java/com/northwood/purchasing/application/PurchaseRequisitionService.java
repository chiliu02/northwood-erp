package com.northwood.purchasing.application;

import com.northwood.purchasing.application.dto.CreateRequisitionCommand;
import com.northwood.purchasing.application.dto.PurchaseRequisitionView;
import com.northwood.purchasing.application.dto.RequisitionLineRequest;
import com.northwood.purchasing.application.dto.StockReplenishmentCommand;
import com.northwood.purchasing.domain.PurchaseRequisition;
import com.northwood.purchasing.domain.PurchaseRequisitionId;
import com.northwood.purchasing.domain.PurchaseRequisitionLine;
import com.northwood.purchasing.domain.PurchaseRequisitionRepository;
import com.northwood.purchasing.domain.Supplier;
import com.northwood.shared.application.exception.ConflictException;
import com.northwood.shared.domain.LineNumbering;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for purchase requisitions. Two creation paths:
 *
 * <ul>
 *   <li>{@link #createManual} — REST entry point for buyers (phase 1
 *       sanity-check).</li>
 *   <li>{@link #createForStockReplenishment} — §2.35 Slice D. Called by
 *       purchasing's {@code ReplenishmentRequestedHandler} when inventory
 *       emits an {@code inventory.ReplenishmentRequested} with
 *       {@code targetService = "purchasing"}. Auto-attaches the default
 *       supplier and emits BOTH {@code PurchaseRequisitionCreated} (with
 *       {@code sourceType='stock_replenishment'} + the
 *       {@code sourceReplenishmentRequestId} field populated) AND
 *       {@code purchasing.ReplenishmentDispatched} to the outbox in the
 *       same transaction so inventory's Slice E close-the-loop handler
 *       picks up the dispatch atomically.</li>
 * </ul>
 *
 * <p>The retired {@code createForWorkOrderShortage} path (removed in §2.35
 * Slice D) is now subsumed by the stock-replenishment path: manufacturing's
 * {@code RawMaterialShortageDetected} flows through inventory's bridge
 * (Slice C) which raises a {@code ReplenishmentRequest} that arrives here
 * the same way as a reorder-point breach.
 *
 * <p>Both paths persist via {@link PurchaseRequisitionRepository#save},
 * which writes pending domain events to the outbox alongside the row insert.
 */
@Service
public class PurchaseRequisitionService {

    public static class ProductDiscontinuedException extends ConflictException {
        public static final String CODE = "PRODUCT_DISCONTINUED";
        private final String sku;
        public ProductDiscontinuedException(String sku) {
            super(CODE, "Product sku=" + sku + " has been discontinued by product-service; "
                + "cannot include it on a new requisition or purchase order");
            this.sku = sku;
        }
        public String sku() { return sku; }
        @Override public Map<String, Object> params() { return Map.of("sku", sku); }
    }

    private static final Logger log = LoggerFactory.getLogger(PurchaseRequisitionService.class);

    private final PurchaseRequisitionRepository purchaseRequisitions;
    private final SupplierQueryPort suppliers;
    private final PurchaseOrderService purchaseOrders;
    private final DiscontinuedProductLookup discontinuedProducts;
    private final boolean shortagePoAutoApprove;

    public PurchaseRequisitionService(
        PurchaseRequisitionRepository purchaseRequisitions,
        SupplierQueryPort suppliers,
        PurchaseOrderService purchaseOrders,
        DiscontinuedProductLookup discontinuedProducts,
        @Value("${northwood.purchasing.shortagePoAutoApprove:true}") boolean shortagePoAutoApprove
    ) {
        this.purchaseRequisitions = purchaseRequisitions;
        this.suppliers = suppliers;
        this.purchaseOrders = purchaseOrders;
        this.discontinuedProducts = discontinuedProducts;
        this.shortagePoAutoApprove = shortagePoAutoApprove;
    }

    @Transactional(readOnly = true)
    public Optional<PurchaseRequisitionView> findById(UUID purchaseRequisitionHeaderId) {
        return purchaseRequisitions.findById(PurchaseRequisitionId.of(purchaseRequisitionHeaderId))
            .map(PurchaseRequisitionView::from);
    }

    @Transactional(readOnly = true)
    public List<PurchaseRequisitionView> findAll() {
        return purchaseRequisitions.findAll().stream().map(PurchaseRequisitionView::from).toList();
    }

    @Transactional
    public PurchaseRequisitionView createManual(CreateRequisitionCommand command) {
        Supplier defaultSupplier = suppliers.defaultSupplier();
        List<PurchaseRequisitionLine> lines = buildLines(command.lines(), defaultSupplier);

        PurchaseRequisition pr = PurchaseRequisition.create(
            command.requisitionNumber(),
            PurchaseRequisition.SourceType.MANUAL,
            null,
            null,
            command.requestedBy(),
            lines
        );
        purchaseRequisitions.save(pr);
        log.info("created manual requisition {} ({} line(s))", pr.requisitionNumber(), lines.size());

        // Manual PRs always land at draft — a human must approve via
        // POST /api/purchase-orders/{id}/approve. autoApprove=false.
        purchaseOrders.convertFromRequisition(pr.id(), false);
        // Reload to capture the side effects of conversion (status update etc.)
        return purchaseRequisitions.findById(pr.id())
            .map(PurchaseRequisitionView::from)
            .orElseThrow();
    }

    /**
     * §2.35 Slice D: create a purchase requisition in response to inventory's
     * {@code ReplenishmentRequested} (targetService=purchasing). Auto-attaches
     * the default supplier, emits PurchaseRequisitionCreated +
     * purchasing.ReplenishmentDispatched, and auto-converts to a PO using
     * the same {@code shortagePoAutoApprove} policy that applied to the
     * retired WO-shortage path.
     */
    @Transactional
    public UUID createForStockReplenishment(StockReplenishmentCommand command) {
        Supplier defaultSupplier = suppliers.defaultSupplier();
        List<PurchaseRequisitionLine> lines = buildLines(command.lines(), defaultSupplier);

        PurchaseRequisition pr = PurchaseRequisition.createForStockReplenishment(
            command.requisitionNumber(),
            command.replenishmentRequestId(),
            "inventory.replenishment-dispatcher",
            lines
        );
        purchaseRequisitions.save(pr);
        log.info("auto-created stock-replenishment requisition {} for replenishment_request={} ({} line(s))",
            pr.requisitionNumber(), command.replenishmentRequestId(), lines.size());

        // Same auto-approve policy as the retired WO-shortage path
        // (northwood.purchasing.shortagePoAutoApprove, default true) — the
        // §2.35 loop flows without a human in the demo path.
        purchaseOrders.convertFromRequisition(pr.id(), shortagePoAutoApprove);
        return pr.id().value();
    }

    private List<PurchaseRequisitionLine> buildLines(
        List<RequisitionLineRequest> requested,
        Supplier defaultSupplier
    ) {
        // §1F.1: reject upfront if any line names a product product-service
        // has discontinued. Same idempotent-projection lookup is used by both
        // manual and shortage-driven PR paths so the shortage detector also
        // gets the rejection (manufacturing emitted the shortage before
        // observing the discontinue is a real race).
        for (RequisitionLineRequest line : requested) {
            if (discontinuedProducts.isDiscontinued(line.productId())) {
                throw new ProductDiscontinuedException(line.productSku());
            }
        }
        List<PurchaseRequisitionLine> built = new ArrayList<>();
        int lineNumber = LineNumbering.START;
        for (RequisitionLineRequest line : requested) {
            built.add(new PurchaseRequisitionLine(
                UUID.randomUUID(),
                lineNumber,
                line.productId(),
                line.productSku(),
                line.productName(),
                line.requestedQuantity(),
                line.requiredDate(),
                defaultSupplier.id().value(),
                defaultSupplier.name(),
                PurchaseRequisition.LineStatus.OPEN
            ));
            lineNumber += LineNumbering.STEP;
        }
        return built;
    }

}
