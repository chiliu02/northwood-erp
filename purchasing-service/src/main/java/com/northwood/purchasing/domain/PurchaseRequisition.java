package com.northwood.purchasing.domain;

import com.northwood.purchasing.domain.events.PurchaseRequisitionCreated;
import com.northwood.purchasing.domain.events.PurchaseRequisitionCreated.RequestedLine;
import com.northwood.purchasing.domain.events.ReplenishmentDispatched;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Human-readable number prefix for new requisitions; stamped by
     * {@code RawMaterialShortageDetectedHandler} (and any future creation
     * path). Pure formatting choice — no consumer dispatches on this value.
     */
    public static final String NUMBER_PREFIX = "PR-";

    /**
     * Character count of the random suffix appended to {@link #NUMBER_PREFIX}
     * when constructing a new requisition number (a
     * {@code UUID.randomUUID().toString().substring(0, …).toUpperCase()}
     * slice). Pairs with the prefix — together they define the full format.
     */
    public static final int NUMBER_SUFFIX_LENGTH = 8;

    /**
     * Purchase-requisition source classifier. Mirrors the schema CHECK on
     * {@code purchasing.purchase_requisition_header.source_type}. Drives which
     * source ids the row carries: {@code MANUAL} → neither, {@code LOW_STOCK}
     * → {@code source_product_id}, {@code WORK_ORDER_SHORTAGE} →
     * {@code source_work_order_id}.
     */
    public enum SourceType {
        MANUAL("manual"),
        /** Schema-prep — not currently produced by Java. */
        LOW_STOCK("low_stock"),
        /**
         * Historical — was the WO-shortage auto-requisition source until
         * the manufacturing-purchasing decoupling retired the producer. Historical rows keep this value; new
         * Java-emitted rows use {@link #STOCK_REPLENISHMENT} instead, with
         * the WO shortage now routed through inventory's
         * {@code ReplenishmentRequest} (see {@code project_235_mfg_pur_decoupling}).
         */
        WORK_ORDER_SHORTAGE("work_order_shortage"),
        /**
         * Created by purchasing's {@code ReplenishmentRequestedHandler}
         * in response to {@code inventory.ReplenishmentRequested} with
         * {@code targetService = "purchasing"}. Carries
         * {@code sourceReplenishmentRequestId}.
         */
        STOCK_REPLENISHMENT("stock_replenishment");

        private final String code;

        SourceType(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static SourceType fromCode(String value) {
            for (SourceType t : values()) {
                if (t.code.equals(value)) return t;
            }
            throw Assert.unknownValue("purchase_requisition source_type", value);
        }
    }

    /**
     * Purchase-requisition header status. Mirrors the schema CHECK on
     * {@code purchasing.purchase_requisition_header.status}. Phase 1 collapses
     * {@code pending_approval → approved} on creation; {@code CONVERTED} is
     * set by {@link #markConverted()} once a PO is created from the PR.
     */
    public enum Status {
        /** Schema-prep — not currently produced by Java. */
        DRAFT("draft"),
        PENDING_APPROVAL("pending_approval"),
        APPROVED("approved"),
        REJECTED("rejected"),
        CONVERTED("converted"),
        /** Schema-prep — not currently produced by Java. */
        CANCELLED("cancelled");

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
            throw Assert.unknownValue("purchase_requisition status", value);
        }
    }

    /**
     * Purchase-requisition line status. Mirrors the schema CHECK on
     * {@code purchasing.purchase_requisition_line.status}. Today's Java only
     * writes {@code OPEN} (initial); {@code CONVERTED} / {@code CANCELLED} are
     * schema-prep for per-line conversion tracking (Phase 2).
     */
    public enum LineStatus {
        OPEN("open"),
        /** Schema-prep — not currently produced by Java. */
        CONVERTED("converted"),
        /** Schema-prep — not currently produced by Java. */
        CANCELLED("cancelled");

        private final String code;

        LineStatus(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static LineStatus fromCode(String value) {
            for (LineStatus s : values()) {
                if (s.code.equals(value)) return s;
            }
            throw Assert.unknownValue("purchase_requisition_line status", value);
        }
    }

    private final PurchaseRequisitionId id;
    private final String requisitionNumber;
    private final SourceType sourceType;
    private final UUID sourceWorkOrderId;
    private final UUID sourceProductId;
    private final UUID sourceReplenishmentRequestId;
    private Status status;
    private final String requestedBy;
    private final List<PurchaseRequisitionLine> lines;
    private final long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /**
     * Factory: a new requisition. Auto-approves at creation (phase 1). Used
     * for {@link SourceType#MANUAL} + {@link SourceType#LOW_STOCK} +
     * {@link SourceType#WORK_ORDER_SHORTAGE} (historical) paths. The
     * {@link SourceType#STOCK_REPLENISHMENT} path goes through
     * {@link #createForStockReplenishment} instead — that factory also
     * emits a sibling {@code purchasing.ReplenishmentDispatched}.
     */
    public static PurchaseRequisition create(
        String requisitionNumber,
        SourceType sourceType,
        UUID sourceWorkOrderId,
        UUID sourceProductId,
        String requestedBy,
        List<PurchaseRequisitionLine> lines
    ) {
        Assert.notNull(sourceType, "sourceType");
        Assert.argument(sourceType != SourceType.STOCK_REPLENISHMENT,
            "use createForStockReplenishment for STOCK_REPLENISHMENT requisitions");
        Assert.notEmpty(lines, "at least one line is required");
        validateSource(sourceType, sourceWorkOrderId, sourceProductId, /* replenishmentRequestId */ null);

        PurchaseRequisitionId id = PurchaseRequisitionId.newId();
        PurchaseRequisition pr = new PurchaseRequisition(
            id, requisitionNumber, sourceType, sourceWorkOrderId, sourceProductId,
            /* sourceReplenishmentRequestId */ null,
            Status.APPROVED, requestedBy, new ArrayList<>(lines), 0L
        );

        pr.pendingEvents.add(buildCreatedEvent(
            id, requisitionNumber, sourceType, sourceWorkOrderId, sourceProductId,
            /* sourceReplenishmentRequestId */ null, lines
        ));
        return pr;
    }

    /**
     * Factory for a stock-replenishment requisition. Identical
     * shape to {@link #create} but with the {@code STOCK_REPLENISHMENT}
     * source type, the {@code sourceReplenishmentRequestId} threaded through,
     * AND a sibling {@link ReplenishmentDispatched} event emitted alongside
     * {@code PurchaseRequisitionCreated} so inventory's
     * close-the-loop handler picks up the dispatch atomically.
     */
    public static PurchaseRequisition createForStockReplenishment(
        String requisitionNumber,
        UUID replenishmentRequestId,
        String requestedBy,
        List<PurchaseRequisitionLine> lines
    ) {
        Assert.notNull(replenishmentRequestId, "replenishmentRequestId");
        Assert.notEmpty(lines, "at least one line is required");

        PurchaseRequisitionId id = PurchaseRequisitionId.newId();
        PurchaseRequisition pr = new PurchaseRequisition(
            id, requisitionNumber, SourceType.STOCK_REPLENISHMENT,
            /* sourceWorkOrderId */ null,
            /* sourceProductId   */ null,
            replenishmentRequestId,
            Status.APPROVED, requestedBy, new ArrayList<>(lines), 0L
        );

        pr.pendingEvents.add(buildCreatedEvent(
            id, requisitionNumber, SourceType.STOCK_REPLENISHMENT,
            null, null, replenishmentRequestId, lines
        ));
        pr.pendingEvents.add(new ReplenishmentDispatched(
            UUID.randomUUID(),
            id.value(),
            replenishmentRequestId,
            Instant.now()
        ));
        return pr;
    }

    private static PurchaseRequisitionCreated buildCreatedEvent(
        PurchaseRequisitionId id,
        String requisitionNumber,
        SourceType sourceType,
        UUID sourceWorkOrderId,
        UUID sourceProductId,
        UUID sourceReplenishmentRequestId,
        List<PurchaseRequisitionLine> lines
    ) {
        List<RequestedLine> wireLines = new ArrayList<>();
        for (PurchaseRequisitionLine l : lines) {
            wireLines.add(new RequestedLine(
                l.id(), l.lineNumber(), l.productId(), l.productSku(), l.productName(),
                l.requestedQuantity(), l.suggestedSupplierId()
            ));
        }
        return new PurchaseRequisitionCreated(
            UUID.randomUUID(),
            id.value(),
            requisitionNumber,
            sourceType.code(),
            sourceWorkOrderId,
            sourceProductId,
            sourceReplenishmentRequestId,
            Status.APPROVED.code(),
            wireLines,
            Instant.now()
        );
    }

    /** Factory: hydrate from the DB; emits no events. */
    public static PurchaseRequisition reconstitute(
        PurchaseRequisitionId id, String requisitionNumber,
        SourceType sourceType, UUID sourceWorkOrderId, UUID sourceProductId,
        UUID sourceReplenishmentRequestId,
        Status status, String requestedBy,
        List<PurchaseRequisitionLine> lines, long version
    ) {
        return new PurchaseRequisition(
            id, requisitionNumber, sourceType, sourceWorkOrderId, sourceProductId,
            sourceReplenishmentRequestId,
            status, requestedBy, new ArrayList<>(lines), version
        );
    }

    private PurchaseRequisition(
        PurchaseRequisitionId id, String requisitionNumber,
        SourceType sourceType, UUID sourceWorkOrderId, UUID sourceProductId,
        UUID sourceReplenishmentRequestId,
        Status status, String requestedBy,
        List<PurchaseRequisitionLine> lines, long version
    ) {
        this.id = id;
        this.requisitionNumber = requisitionNumber;
        this.sourceType = sourceType;
        this.sourceWorkOrderId = sourceWorkOrderId;
        this.sourceProductId = sourceProductId;
        this.sourceReplenishmentRequestId = sourceReplenishmentRequestId;
        this.status = status;
        this.requestedBy = requestedBy;
        this.lines = lines;
        this.version = version;
    }

    private static void validateSource(
        SourceType sourceType, UUID workOrderId, UUID productId, UUID replenishmentRequestId
    ) {
        switch (sourceType) {
            case MANUAL -> Assert.argument(
                workOrderId == null && productId == null && replenishmentRequestId == null,
                "manual requisitions cannot carry source ids");
            case LOW_STOCK -> Assert.argument(
                productId != null && workOrderId == null && replenishmentRequestId == null,
                "low_stock requisitions need source_product_id only");
            case WORK_ORDER_SHORTAGE -> Assert.argument(
                workOrderId != null && productId == null && replenishmentRequestId == null,
                "work_order_shortage requisitions need source_work_order_id only");
            case STOCK_REPLENISHMENT -> Assert.argument(
                replenishmentRequestId != null && workOrderId == null && productId == null,
                "stock_replenishment requisitions need source_replenishment_request_id only");
        }
    }

    /**
     * Mark this requisition as converted to a purchase order. Idempotent — a
     * requisition already in {@code converted} is a no-op so a redelivered
     * trigger doesn't trip optimistic concurrency. Other terminal states
     * ({@code rejected}, {@code cancelled}) reject the call.
     */
    public void markConverted() {
        if (status == Status.CONVERTED) {
            return;
        }
        Assert.state(status == Status.APPROVED, "Cannot convert requisition " + id.value() + " from status=" + status.code());
        this.status = Status.CONVERTED;
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    public PurchaseRequisitionId id()                       { return id; }
    public String requisitionNumber()                       { return requisitionNumber; }
    public SourceType sourceType()                          { return sourceType; }
    public UUID sourceWorkOrderId()                         { return sourceWorkOrderId; }
    public UUID sourceProductId()                           { return sourceProductId; }
    public UUID sourceReplenishmentRequestId()              { return sourceReplenishmentRequestId; }
    public Status status()                                  { return status; }
    public String requestedBy()                             { return requestedBy; }
    public List<PurchaseRequisitionLine> lines()            { return List.copyOf(lines); }
    public long version()                                   { return version; }
}
