package com.northwood.purchasing.application;

import com.northwood.purchasing.application.dto.CreateRequisitionCommand;
import com.northwood.purchasing.application.dto.PurchaseRequisitionView;
import com.northwood.purchasing.application.dto.RequisitionLineRequest;
import com.northwood.purchasing.application.dto.WorkOrderShortageCommand;
import com.northwood.purchasing.domain.PurchaseRequisition;
import com.northwood.purchasing.domain.PurchaseRequisitionId;
import com.northwood.purchasing.domain.PurchaseRequisitionLine;
import com.northwood.purchasing.domain.PurchaseRequisitionRepository;
import com.northwood.purchasing.domain.Supplier;
import java.util.ArrayList;
import java.util.List;
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
 *   <li>{@link #createForWorkOrderShortage} — called by the inbox handler
 *       when manufacturing reports a raw-material shortage. Auto-attaches
 *       the default supplier and emits {@code PurchaseRequisitionCreated}
 *       to the outbox in the same transaction.</li>
 * </ul>
 *
 * <p>Both paths persist via {@link PurchaseRequisitionRepository#save},
 * which writes pending domain events to the outbox alongside the row insert.
 */
@Service
public class PurchaseRequisitionService {

    public static class ProductDiscontinuedException extends RuntimeException {
        public ProductDiscontinuedException(String sku) {
            super("Product sku=" + sku + " has been discontinued by product-service; "
                + "cannot include it on a new requisition or purchase order");
        }
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

    @Transactional
    public UUID createForWorkOrderShortage(WorkOrderShortageCommand command) {
        Supplier defaultSupplier = suppliers.defaultSupplier();
        List<PurchaseRequisitionLine> lines = buildLines(command.lines(), defaultSupplier);

        PurchaseRequisition pr = PurchaseRequisition.create(
            command.requisitionNumber(),
            PurchaseRequisition.SourceType.WORK_ORDER_SHORTAGE,
            command.workOrderId(),
            null,
            "manufacturing.shortage-detector",
            lines
        );
        purchaseRequisitions.save(pr);
        log.info("auto-created shortage requisition {} for work_order={} ({} line(s))",
            pr.requisitionNumber(), command.workOrderId(), lines.size());

        // Shortage flow respects northwood.purchasing.shortagePoAutoApprove
        // (default true) — the make-to-order saga can flow without a human.
        // Set false to force human approval even for shortage-driven POs.
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
        int lineNumber = 10;
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
            lineNumber += 10;
        }
        return built;
    }

}
