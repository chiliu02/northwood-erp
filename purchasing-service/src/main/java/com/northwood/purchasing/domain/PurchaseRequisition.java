package com.northwood.purchasing.domain;

import com.northwood.purchasing.domain.events.PurchaseRequisitionCreated;
import com.northwood.purchasing.domain.events.PurchaseRequisitionCreated.RequestedLine;
import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for a purchase requisition: header + lines. Three sources
 * (matching the schema's CHECK constraint):
 *
 * <ul>
 *   <li>{@code manual} — created via the REST endpoint by a buyer.</li>
 *   <li>{@code low_stock} — auto-created by inventory's reorder-policy
 *       trigger (out of scope for phase 1).</li>
 *   <li>{@code work_order_shortage} — auto-created by purchasing's inbox
 *       handler when manufacturing reports a raw-material shortage.</li>
 * </ul>
 *
 * <p>Phase 1 simplification: every requisition is auto-approved at creation
 * time. The {@code draft / pending_approval / approved} progression collapses
 * to a single {@code approved} status. A real workflow lands when the
 * approval user story does.
 */
public final class PurchaseRequisition {

    /**
     * Wire-format aggregate-type stamped onto {@code purchasing.outbox_message.aggregate_type}
     * for events this aggregate emits.
     */
    public static final String AGGREGATE_TYPE = PurchasingAggregateTypes.PURCHASE_REQUISITION;

    // ------------------------------------------------------------
    // Status constants — wire-format strings stored in
    // purchasing.purchase_requisition_header.status. Phase 1 collapses
    // pending_approval → approved on creation; converted is set by
    // markConverted() once a PO is created from the PR.
    // ------------------------------------------------------------
    public static final String PENDING_APPROVAL = "pending_approval";
    public static final String APPROVED = "approved";
    public static final String CONVERTED = "converted";
    public static final String REJECTED = "rejected";


    private final PurchaseRequisitionId id;
    private final String requisitionNumber;
    private final String sourceType;
    private final UUID sourceWorkOrderId;
    private final UUID sourceProductId;
    private String status;
    private final String requestedBy;
    private final List<PurchaseRequisitionLine> lines;
    private final long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /** Factory: a new requisition. Auto-approves at creation (phase 1). */
    public static PurchaseRequisition create(
        String requisitionNumber,
        String sourceType,
        UUID sourceWorkOrderId,
        UUID sourceProductId,
        String requestedBy,
        List<PurchaseRequisitionLine> lines
    ) {
        Objects.requireNonNull(sourceType, "sourceType");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("at least one line is required");
        }
        validateSource(sourceType, sourceWorkOrderId, sourceProductId);

        PurchaseRequisitionId id = PurchaseRequisitionId.newId();
        PurchaseRequisition pr = new PurchaseRequisition(
            id, requisitionNumber, sourceType, sourceWorkOrderId, sourceProductId,
            APPROVED, requestedBy, new ArrayList<>(lines), 0L
        );

        List<RequestedLine> wireLines = new ArrayList<>();
        for (PurchaseRequisitionLine l : lines) {
            wireLines.add(new RequestedLine(
                l.id(), l.lineNumber(), l.productId(), l.productSku(), l.productName(),
                l.requestedQuantity(), l.suggestedSupplierId()
            ));
        }
        pr.pendingEvents.add(new PurchaseRequisitionCreated(
            UUID.randomUUID(),
            id.value(),
            requisitionNumber,
            sourceType,
            sourceWorkOrderId,
            sourceProductId,
            APPROVED,
            wireLines,
            Instant.now()
        ));
        return pr;
    }

    /** Factory: hydrate from the DB; emits no events. */
    public static PurchaseRequisition reconstitute(
        PurchaseRequisitionId id, String requisitionNumber,
        String sourceType, UUID sourceWorkOrderId, UUID sourceProductId,
        String status, String requestedBy,
        List<PurchaseRequisitionLine> lines, long version
    ) {
        return new PurchaseRequisition(
            id, requisitionNumber, sourceType, sourceWorkOrderId, sourceProductId,
            status, requestedBy, new ArrayList<>(lines), version
        );
    }

    private PurchaseRequisition(
        PurchaseRequisitionId id, String requisitionNumber,
        String sourceType, UUID sourceWorkOrderId, UUID sourceProductId,
        String status, String requestedBy,
        List<PurchaseRequisitionLine> lines, long version
    ) {
        this.id = id;
        this.requisitionNumber = requisitionNumber;
        this.sourceType = sourceType;
        this.sourceWorkOrderId = sourceWorkOrderId;
        this.sourceProductId = sourceProductId;
        this.status = status;
        this.requestedBy = requestedBy;
        this.lines = lines;
        this.version = version;
    }

    private static void validateSource(String sourceType, UUID workOrderId, UUID productId) {
        switch (sourceType) {
            case "manual" -> {
                if (workOrderId != null || productId != null) {
                    throw new IllegalArgumentException("manual requisitions cannot carry source ids");
                }
            }
            case "low_stock" -> {
                if (productId == null || workOrderId != null) {
                    throw new IllegalArgumentException("low_stock requisitions need source_product_id only");
                }
            }
            case "work_order_shortage" -> {
                if (workOrderId == null || productId != null) {
                    throw new IllegalArgumentException("work_order_shortage requisitions need source_work_order_id only");
                }
            }
            default -> throw new IllegalArgumentException("unknown sourceType=" + sourceType);
        }
    }

    /**
     * Mark this requisition as converted to a purchase order. Idempotent — a
     * requisition already in {@code converted} is a no-op so a redelivered
     * trigger doesn't trip optimistic concurrency. Other terminal states
     * ({@code rejected}, {@code cancelled}) reject the call.
     */
    public void markConverted() {
        if (CONVERTED.equals(status)) {
            return;
        }
        if (!APPROVED.equals(status)) {
            throw new IllegalStateException(
                "Cannot convert requisition " + id.value() + " from status=" + status
            );
        }
        this.status = CONVERTED;
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    public PurchaseRequisitionId id()                       { return id; }
    public String requisitionNumber()                       { return requisitionNumber; }
    public String sourceType()                              { return sourceType; }
    public UUID sourceWorkOrderId()                         { return sourceWorkOrderId; }
    public UUID sourceProductId()                           { return sourceProductId; }
    public String status()                                  { return status; }
    public String requestedBy()                             { return requestedBy; }
    public List<PurchaseRequisitionLine> lines()            { return List.copyOf(lines); }
    public long version()                                   { return version; }
}
