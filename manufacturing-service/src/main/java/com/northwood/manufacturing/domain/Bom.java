package com.northwood.manufacturing.domain;

import com.northwood.manufacturing.domain.events.BomActivated;
import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for a Bill of Materials — header + lines. A BOM lives in one
 * of three states: {@code DRAFT} (editable; new lines may be added, existing
 * lines removed), {@code ACTIVE} (frozen; work orders snapshot from it at
 * release time), {@code INACTIVE} (superseded by a later active version).
 *
 * <p>Promoted from a row-level write port 2026-05-16 (§2.16). Previously
 * {@code BomEditRepository} carried row-shaped methods ({@code insertHeader},
 * {@code insertLine}, {@code markActive}) and the application service held the
 * state-machine invariants. Now the aggregate owns the state machine; the
 * repository persists the aggregate and the application service orchestrates
 * cross-aggregate concerns (cycle detection via {@link BomCycleDetector},
 * downstream rollup recompute).
 *
 * <p><strong>Cycle detection is application-orchestrated, not aggregate-internal.</strong>
 * The cycle invariant is fundamentally about the BOM graph (multiple Bom
 * aggregates' lines forming a directed edge set). {@link BomCycleDetector}
 * walks that graph by querying the DB, so cycle checks run inside the
 * application service's transaction <em>after</em> {@code save()} — the
 * detector sees the just-persisted state and the surrounding {@code @Transactional}
 * rolls back the save on a positive cycle finding. This is a documented
 * deviation from the §2.16 backlog's "pass {@code BomCycleDetector} into
 * {@link #activate} as a method parameter" wording: that shape would require
 * the aggregate to walk other aggregates' data, which is exactly what
 * domain services exist to externalise. The application service owns the
 * cross-aggregate check; the aggregate owns its own state-machine invariants.
 *
 * <p>Today's only emitted event is {@link BomActivated}. {@code addLine} /
 * {@code removeLine} mutate state without emitting events — too granular for
 * any consumer to care about, and a future addition is non-breaking.
 */
public final class Bom {

    /**
     * Wire-format aggregate-type stamped onto {@code manufacturing.outbox_message.aggregate_type}
     * for events this aggregate emits.
     */
    public static final String AGGREGATE_TYPE = ManufacturingAggregateTypes.BOM;

    /**
     * BOM lifecycle status. Mirrors the schema CHECK on
     * {@code manufacturing.bom_header.status}. Lifecycle: {@code DRAFT}
     * (editable) → {@code ACTIVE} (frozen) → {@code INACTIVE} (superseded).
     */
    public enum Status {
        DRAFT("draft"),
        ACTIVE("active"),
        INACTIVE("inactive");

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
            throw new IllegalArgumentException("Unknown bom status: " + value);
        }
    }

    /**
     * BOM-line component classification. Mirrors the schema CHECK on
     * {@code manufacturing.bom_line.component_kind}. {@code RAW} for purchased
     * materials; {@code SUB_ASSEMBLY} for components that themselves have an
     * active BOM (recursive case — planning logic recurses on these).
     */
    public enum ComponentKind {
        RAW("raw"),
        SUB_ASSEMBLY("sub_assembly");

        private final String dbValue;

        ComponentKind(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static ComponentKind fromDb(String value) {
            for (ComponentKind k : values()) {
                if (k.dbValue.equals(value)) return k;
            }
            throw new IllegalArgumentException("Unknown bom_line component_kind: " + value);
        }
    }

    private final BomId id;
    private final UUID finishedProductId;
    private final String finishedProductSku;
    private final String finishedProductName;
    private final String version;
    private Status status;
    private final List<BomLine> lines;
    private final long aggregateVersion;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /**
     * Lines added since reconstitute (or since {@link #pullAddedLines}). The
     * repository INSERTs these on {@code save()}. For a freshly-drafted aggregate
     * (created via {@link #draft}), this collects every line up to the first save.
     */
    private final List<BomLine> addedLines = new ArrayList<>();

    /**
     * Ids of pre-existing lines (loaded via {@link #reconstitute}) that
     * {@link #removeLine} has removed since the last save. The repository DELETEs
     * these on {@code save()}. Lines that were added and then removed in the same
     * session never appear here — they're pruned from {@link #addedLines} instead.
     */
    private final List<BomLineId> removedLineIds = new ArrayList<>();

    private Bom(
        BomId id,
        UUID finishedProductId,
        String finishedProductSku,
        String finishedProductName,
        String version,
        Status status,
        List<BomLine> lines,
        long aggregateVersion
    ) {
        this.id = id;
        this.finishedProductId = finishedProductId;
        this.finishedProductSku = finishedProductSku;
        this.finishedProductName = finishedProductName;
        this.version = version;
        this.status = status;
        this.lines = lines;
        this.aggregateVersion = aggregateVersion;
    }

    /** Mint a new BOM in {@code DRAFT} status with no lines. Emits nothing. */
    public static Bom draft(
        UUID finishedProductId,
        String finishedProductSku,
        String finishedProductName,
        String version
    ) {
        Objects.requireNonNull(finishedProductId, "finishedProductId");
        Objects.requireNonNull(finishedProductSku, "finishedProductSku");
        Objects.requireNonNull(finishedProductName, "finishedProductName");
        Objects.requireNonNull(version, "version");
        if (finishedProductSku.isBlank()) {
            throw new IllegalArgumentException("finishedProductSku must not be blank");
        }
        if (finishedProductName.isBlank()) {
            throw new IllegalArgumentException("finishedProductName must not be blank");
        }
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        return new Bom(
            BomId.newId(),
            finishedProductId,
            finishedProductSku,
            finishedProductName,
            version,
            Status.DRAFT,
            new ArrayList<>(),
            0L
        );
    }

    /** Reconstitute from persistence. Emits nothing. */
    public static Bom reconstitute(
        BomId id,
        UUID finishedProductId,
        String finishedProductSku,
        String finishedProductName,
        String version,
        Status status,
        List<BomLine> lines,
        long aggregateVersion
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(finishedProductId, "finishedProductId");
        Objects.requireNonNull(finishedProductSku, "finishedProductSku");
        Objects.requireNonNull(finishedProductName, "finishedProductName");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(lines, "lines");
        return new Bom(
            id,
            finishedProductId,
            finishedProductSku,
            finishedProductName,
            version,
            status,
            new ArrayList<>(lines),
            aggregateVersion
        );
    }

    /**
     * Append a new line to a draft BOM. Allocates the next {@code lineNumber}
     * monotonically from the current lines' max. Rejects on non-draft status
     * and on self-reference (component equals the BOM's finished product).
     *
     * <p>The cycle invariant is enforced one layer up in the application
     * service (post-save call to {@link BomCycleDetector}) — this mutator
     * doesn't run the detector and doesn't know about the graph beyond this
     * single BOM.
     */
    public BomLine addLine(BomLine.Spec spec) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(spec.componentProductId(), "componentProductId");
        Objects.requireNonNull(spec.componentSku(), "componentSku");
        Objects.requireNonNull(spec.componentName(), "componentName");
        Objects.requireNonNull(spec.componentKind(), "componentKind");
        Objects.requireNonNull(spec.quantityPerFinishedUnit(), "quantityPerFinishedUnit");
        if (status != Status.DRAFT) {
            throw new BomNotEditableException(
                "BOM " + id.value() + " is " + status + "; only DRAFT BOMs accept addLine"
            );
        }
        if (spec.componentProductId().equals(finishedProductId)) {
            throw new BomCycleException(
                "Component cannot equal the BOM's finished product (" + finishedProductId + ")"
            );
        }
        int nextLineNumber = nextLineNumber();
        BomLine line = new BomLine(
            BomLineId.newId(),
            nextLineNumber,
            spec.componentProductId(),
            spec.componentSku(),
            spec.componentName(),
            spec.componentKind(),
            spec.quantityPerFinishedUnit(),
            spec.scrapFactorPercent()
        );
        lines.add(line);
        addedLines.add(line);
        return line;
    }

    /** Remove a line from a draft BOM. Returns true iff the line existed. Rejects on non-draft status. */
    public boolean removeLine(BomLineId bomLineId) {
        Objects.requireNonNull(bomLineId, "bomLineId");
        if (status != Status.DRAFT) {
            throw new BomNotEditableException(
                "BOM " + id.value() + " is " + status + "; only DRAFT BOMs accept removeLine"
            );
        }
        boolean wasAddedThisSession = addedLines.removeIf(line -> line.id().equals(bomLineId));
        boolean removedFromLines = lines.removeIf(line -> line.id().equals(bomLineId));
        if (removedFromLines && !wasAddedThisSession) {
            // Line was loaded from DB — repository needs to DELETE it on save.
            removedLineIds.add(bomLineId);
        }
        return removedFromLines;
    }

    /**
     * Activate a draft BOM. Flips status to {@code ACTIVE} and emits
     * {@link BomActivated}. No-op (no event, no version bump) when already
     * active. Rejects on non-draft non-active status, or when the BOM has no
     * lines.
     *
     * <p>The cycle invariant on activation is enforced by the application
     * service after {@code save()}: it walks {@link BomCycleDetector} over
     * every component product id in {@link #lines()} and rolls back the
     * transaction on a positive finding. See the class Javadoc for why that
     * lives in the service rather than here.
     */
    public void activate() {
        if (status == Status.ACTIVE) {
            return;
        }
        if (status != Status.DRAFT) {
            throw new BomNotEditableException(
                "Cannot activate BOM " + id.value() + " from status=" + status
            );
        }
        if (lines.isEmpty()) {
            throw new BomNotEditableException(
                "Cannot activate BOM " + id.value() + " with no lines"
            );
        }
        this.status = Status.ACTIVE;
        this.pendingEvents.add(new BomActivated(
            UUID.randomUUID(),
            id.value(),
            finishedProductId,
            finishedProductSku,
            version,
            Instant.now()
        ));
    }

    private int nextLineNumber() {
        int max = 0;
        for (BomLine line : lines) {
            if (line.lineNumber() > max) max = line.lineNumber();
        }
        return max + 1;
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    /** Lines added this session — to be INSERTed by the repository on save. */
    public List<BomLine> pullAddedLines() {
        List<BomLine> out = List.copyOf(addedLines);
        addedLines.clear();
        return out;
    }

    /** Line ids removed this session — to be DELETEd by the repository on save. */
    public List<BomLineId> pullRemovedLineIds() {
        List<BomLineId> out = List.copyOf(removedLineIds);
        removedLineIds.clear();
        return out;
    }

    public BomId id()                       { return id; }
    public UUID finishedProductId()         { return finishedProductId; }
    public String finishedProductSku()      { return finishedProductSku; }
    public String finishedProductName()     { return finishedProductName; }
    public String version()                 { return version; }
    public Status status()                  { return status; }
    public List<BomLine> lines()            { return List.copyOf(lines); }
    public long aggregateVersion()          { return aggregateVersion; }

    /** Number of lines currently on this BOM. */
    public int lineCount() {
        return lines.size();
    }

    /** Component product ids across all lines, in line-number order. */
    public List<UUID> componentProductIds() {
        return lines.stream().map(BomLine::componentProductId).toList();
    }

    public static class BomNotEditableException extends RuntimeException {
        public BomNotEditableException(String message) {
            super(message);
        }
    }

    public static class BomCycleException extends RuntimeException {
        public BomCycleException(String message) {
            super(message);
        }
    }
}
