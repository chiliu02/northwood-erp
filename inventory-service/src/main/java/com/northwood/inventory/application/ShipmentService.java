package com.northwood.inventory.application;

import com.northwood.inventory.application.dto.PostShipmentCommand;
import com.northwood.inventory.application.dto.ShipmentLineRequest;
import com.northwood.inventory.application.dto.ShipmentView;
import com.northwood.inventory.application.inbox.SalesOrderLineFactsProjection;
import com.northwood.inventory.application.inbox.SalesOrderLineFactsProjection.PrepaymentGate;
import com.northwood.inventory.application.replenishment.ReplenishmentDetectionService;
import com.northwood.inventory.domain.Shipment;
import com.northwood.inventory.domain.ShipmentId;
import com.northwood.inventory.domain.ShipmentLine;
import com.northwood.inventory.domain.ShipmentRepository;
import com.northwood.inventory.domain.StockMovementDirection;
import com.northwood.inventory.domain.StockMovementSourceTypes;
import com.northwood.inventory.domain.StockMovementType;
import com.northwood.inventory.domain.WarehouseCodes;
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
 * Application service for shipments. Posts a shipment against a sales order
 * and emits {@code inventory.ShipmentPosted}. Sales' inbox handler advances
 * the fulfilment saga {@code ready_to_ship → goods_shipped} and emits a
 * richer {@code SalesOrderShipped} event for finance.
 *
 * <p>Stock-balance accounting (wired with the production-confirmation slice):
 * for each shipped line we decrement {@code on_hand_quantity} by the shipped
 * qty AND release any matching reservation by reducing
 * {@code reserved_quantity} by {@code min(reserved, shipped)}. The "make-to-
 * order with failed reservation" path leaves {@code reserved_quantity = 0}
 * for the FG, so the release becomes a no-op there; the manufacturing-
 * confirmation handler had bumped {@code on_hand_quantity} to a positive
 * value before the shipment, so the decrement no longer goes negative.
 *
 * <p>Public methods return {@link ShipmentView} rather than the
 * {@code Shipment} aggregate — see CLAUDE.md "Controllers (api/) must depend
 * only on application/".
 */
@Service
public class ShipmentService {

    /**
     * Thrown when a shipment line names a {@code sales_order_line_id} that
     * either doesn't appear in {@code sales_order_line_facts} or maps to a
     * different {@code product_id} than the line claims. Mapped to HTTP 400
     * by the controller. Defence-in-depth: catches a buggy / malicious client
     * that bypassed the SPA picker.
     */
    public static class ShipmentLineProductMismatchException extends BadRequestException {
        public static final String CODE = "SHIPMENT_LINE_PRODUCT_MISMATCH";
        private final UUID salesOrderLineId;
        private final UUID expectedProductId;
        private final UUID actualProductId;
        public ShipmentLineProductMismatchException(UUID salesOrderLineId, UUID expectedProductId, UUID actualProductId) {
            super(CODE, expectedProductId == null
                ? "Unknown sales_order_line_id=%s (no matching projection row; line may not belong to a placed sales order)".formatted(salesOrderLineId)
                : "Product mismatch on sales_order_line_id=%s: expected product=%s, got=%s".formatted(salesOrderLineId, expectedProductId, actualProductId)
            );
            this.salesOrderLineId = salesOrderLineId;
            this.expectedProductId = expectedProductId;
            this.actualProductId = actualProductId;
        }
        public UUID salesOrderLineId() { return salesOrderLineId; }
        public UUID expectedProductId() { return expectedProductId; }
        public UUID actualProductId() { return actualProductId; }
        @Override public Map<String, Object> params() {
            // expectedProductId / actualProductId are null in the "unknown
            // line id" branch — omit rather than insert nulls (Map.of / copyOf
            // reject nulls; the SPA falls back on missing keys cleanly).
            Map<String, Object> p = new HashMap<>();
            p.put("salesOrderLineId", salesOrderLineId);
            if (expectedProductId != null) p.put("expectedProductId", expectedProductId);
            if (actualProductId != null) p.put("actualProductId", actualProductId);
            return Map.copyOf(p);
        }
    }

    /**
     * §2.31 Slice C. Thrown when {@code ShipmentService.post} is asked to
     * dispatch a prepayment-terms sales order whose prepayment invoice
     * hasn't been fully paid yet. Mapped to HTTP 409. Inventory reads
     * {@code inventory.sales_order_line_facts.prepayment_settled} (flipped
     * true by {@code sales.SalesOrderPrepaymentSettled}) — no cross-context
     * read into sales' saga.
     */
    public static class UnpaidPrepaymentOrderException extends ConflictException {
        public static final String CODE = "UNPAID_PREPAYMENT_ORDER";
        private final UUID salesOrderHeaderId;
        public UnpaidPrepaymentOrderException(UUID salesOrderHeaderId) {
            super(CODE, "Cannot ship sales_order=%s — payment_terms='prepayment' and prepayment invoice is not yet fully paid"
                .formatted(salesOrderHeaderId));
            this.salesOrderHeaderId = salesOrderHeaderId;
        }
        public UUID salesOrderHeaderId() { return salesOrderHeaderId; }
        @Override public Map<String, Object> params() {
            return Map.of("salesOrderHeaderId", salesOrderHeaderId);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ShipmentService.class);

    private final ShipmentRepository shipments;
    private final StockBalanceWriter stockBalances;
    private final StockMovementWriter movements;
    private final WarehouseLookup warehouses;
    private final SalesOrderLineFactsProjection salesOrderLineFacts;
    private final ReplenishmentDetectionService replenishmentDetection;

    public ShipmentService(
        ShipmentRepository shipments,
        StockBalanceWriter stockBalances,
        StockMovementWriter movements,
        WarehouseLookup warehouses,
        SalesOrderLineFactsProjection salesOrderLineFacts,
        ReplenishmentDetectionService replenishmentDetection
    ) {
        this.shipments = shipments;
        this.stockBalances = stockBalances;
        this.movements = movements;
        this.warehouses = warehouses;
        this.salesOrderLineFacts = salesOrderLineFacts;
        this.replenishmentDetection = replenishmentDetection;
    }

    @Transactional(readOnly = true)
    public Optional<ShipmentView> findById(UUID id) {
        return shipments.findById(ShipmentId.of(id)).map(ShipmentView::from);
    }

    @Transactional(readOnly = true)
    public List<ShipmentView> findAllHeaders() {
        return shipments.findAllHeaders().stream().map(ShipmentView::from).toList();
    }

    @Transactional
    public ShipmentView post(PostShipmentCommand command) {
        String warehouseCode = command.warehouseCode() == null ? WarehouseCodes.MAIN : command.warehouseCode();
        UUID warehouseId = warehouses.findIdByCode(warehouseCode);

        // §2.31 Slice C: gate prepayment orders on prepayment_settled. The
        // header-level facts are denormalised onto each line of
        // sales_order_line_facts (one query for both validations). Skip when
        // no sales_order_header_id is supplied — unlinked manual shipments
        // are an existing affordance.
        if (command.salesOrderHeaderId() != null) {
            Optional<PrepaymentGate> gate = salesOrderLineFacts.findPrepaymentGate(command.salesOrderHeaderId());
            if (gate.isPresent()
                && "prepayment".equals(gate.get().paymentTerms())
                && !gate.get().prepaymentSettled()) {
                throw new UnpaidPrepaymentOrderException(command.salesOrderHeaderId());
            }
        }

        // Defence-in-depth: reject if any line's productId doesn't match the
        // projected sales_order_line_facts for the named sales_order_line_id.
        // Lines without a sales_order_line_id (unlinked manual shipments) skip
        // the check — that's an existing API affordance.
        for (ShipmentLineRequest line : command.lines()) {
            if (line.salesOrderLineId() == null) {
                continue;
            }
            Optional<UUID> expected = salesOrderLineFacts.findProductIdForLine(line.salesOrderLineId());
            if (expected.isEmpty() || !expected.get().equals(line.productId())) {
                throw new ShipmentLineProductMismatchException(
                    line.salesOrderLineId(), expected.orElse(null), line.productId()
                );
            }
        }

        List<ShipmentLine> lines = new ArrayList<>();
        for (ShipmentLineRequest line : command.lines()) {
            BigDecimal unitCost = line.unitCost() == null ? BigDecimal.ZERO : line.unitCost();
            BigDecimal lineCost = line.shippedQuantity().multiply(unitCost);
            lines.add(new ShipmentLine(
                UUID.randomUUID(),
                line.salesOrderLineId(),
                line.productId(),
                line.productSku(),
                line.productName(),
                line.shippedQuantity(),
                unitCost,
                lineCost
            ));
        }

        Shipment shipment = Shipment.post(
            command.shipmentNumber(),
            command.salesOrderHeaderId(),
            command.customerId(),
            command.customerName(),
            warehouseId,
            warehouseCode,
            lines
        );
        shipments.save(shipment);

        for (ShipmentLine l : lines) {
            stockBalances.decrementOnHandAndReleaseReserved(warehouseId, l.productId(), l.shippedQuantity());
            movements.record(
                warehouseId, l.productId(), l.productSku(), l.productName(),
                StockMovementType.SALES_SHIPMENT, StockMovementDirection.OUT,
                l.shippedQuantity(), l.unitCost(),
                StockMovementSourceTypes.SHIPMENT, shipment.id().value(), l.id()
            );
            // §2.35 Slice B: if this decrement brings on_hand below reorder_point,
            // raise an inventory.ReplenishmentRequest (routed by make-vs-buy).
            // Same @Transactional boundary → outbox event lands atomically with
            // the balance write.
            replenishmentDetection.checkAfterOnHandDecrement(warehouseId, l.productId());
        }

        log.info("posted shipment {} for sales_order={} ({} line(s)) at warehouse={}",
            shipment.shipmentNumber(), command.salesOrderHeaderId(),
            lines.size(), warehouseCode);
        return ShipmentView.from(shipment);
    }

}
