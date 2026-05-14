package com.northwood.manufacturing.application;

import com.northwood.manufacturing.domain.BomCycleDetector;
import com.northwood.manufacturing.domain.BomEditRepository;
import com.northwood.manufacturing.domain.BomEditRepository.HeaderRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Editorial commands on {@code manufacturing.bom_header} / {@code bom_line}.
 * The job of this service is to keep the BOM graph acyclic — every mutation
 * that could introduce a cycle runs the {@link BomCycleDetector} inside the
 * same transaction as the change, so a concurrent edit can't slip a cycle in
 * between detection and commit.
 *
 * <p>Three commands are supported:
 *
 * <ul>
 *   <li>{@code addLine}: insert a {@code bom_line} into a non-active BOM,
 *       check for cycles, rollback on detection.</li>
 *   <li>{@code removeLine}: delete a {@code bom_line} from a non-active BOM.
 *       Removing edges cannot create cycles, so no detector run.</li>
 *   <li>{@code activate}: flip a draft BOM to {@code 'active'} (the partial
 *       unique index on {@code (finished_product_id) WHERE status='active'}
 *       enforces "at most one active per product" at the DB level), and check
 *       that the new edge set doesn't close a cycle through previously-inactive
 *       sub-assemblies.</li>
 * </ul>
 *
 * <p>Edits are rejected on active BOMs because work orders snapshot from
 * active BOMs at release time — letting the source change underneath them
 * leads to ambiguous "which version am I looking at" reads. Make a new draft,
 * activate it; the old one becomes {@code 'inactive'} via a separate
 * deactivate command (not implemented in this slice — single-active means a
 * straight activate of a sibling draft will fail until the existing active is
 * deactivated).
 */
@Service
public class BomEditService {

    public record CreateBomDraftCommand(
        UUID finishedProductId,
        String finishedProductSku,
        String finishedProductName,
        String version
    ) {}

    public record AddLineCommand(
        UUID componentProductId,
        String componentSku,
        String componentName,
        String componentKind,
        BigDecimal quantityPerFinishedUnit,
        BigDecimal scrapFactorPercent
    ) {}

    public static class BomNotFoundException extends RuntimeException {
        public BomNotFoundException(UUID bomHeaderId) {
            super("No BOM with bom_header_id=" + bomHeaderId);
        }
    }

    public static class BomLineNotFoundException extends RuntimeException {
        public BomLineNotFoundException(UUID bomLineId) {
            super("No BOM line with bom_line_id=" + bomLineId);
        }
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

    // BOM header status — wire-format strings stored in manufacturing.bom_header.status.
    private static final String BOM_STATUS_DRAFT = "draft";
    private static final String BOM_STATUS_ACTIVE = "active";

    private static final Logger log = LoggerFactory.getLogger(BomEditService.class);

    private final BomEditRepository bomEdits;
    private final BomCycleDetector cycleDetector;
    private final MaterialsCostRollupService rollup;

    public BomEditService(
        BomEditRepository bomEdits,
        BomCycleDetector cycleDetector,
        MaterialsCostRollupService rollup
    ) {
        this.bomEdits = bomEdits;
        this.cycleDetector = cycleDetector;
        this.rollup = rollup;
    }

    /**
     * Create a new BOM draft. Returns the new {@code bom_header_id} so
     * subsequent {@link #addLine} / {@link #activate} calls can target it.
     * The BOM starts at status {@code 'draft'} with no lines; the unique
     * constraint on {@code (finished_product_id, version)} prevents two
     * drafts with the same version label colliding for the same product.
     */
    @Transactional
    public UUID createDraft(CreateBomDraftCommand command) {
        if (command.finishedProductId() == null) {
            throw new IllegalArgumentException("finishedProductId required");
        }
        if (command.finishedProductSku() == null || command.finishedProductSku().isBlank()) {
            throw new IllegalArgumentException("finishedProductSku required");
        }
        if (command.finishedProductName() == null || command.finishedProductName().isBlank()) {
            throw new IllegalArgumentException("finishedProductName required");
        }
        UUID bomHeaderId = UUID.randomUUID();
        String version = command.version() == null || command.version().isBlank()
            ? "1" : command.version();
        bomEdits.insertHeader(
            bomHeaderId, command.finishedProductId(),
            command.finishedProductSku(), command.finishedProductName(),
            version
        );
        log.info("created bom_header {} (draft) for product {} version {}",
            bomHeaderId, command.finishedProductSku(), version);
        return bomHeaderId;
    }

    @Transactional
    public UUID addLine(UUID bomHeaderId, AddLineCommand command) {
        HeaderRow header = loadEditableHeader(bomHeaderId);

        if (header.finishedProductId().equals(command.componentProductId)) {
            throw new BomCycleException(
                "Component cannot equal the BOM's finished product (" + header.finishedProductId() + ")"
            );
        }

        int nextLineNumber = bomEdits.nextLineNumber(bomHeaderId);

        UUID bomLineId = UUID.randomUUID();
        bomEdits.insertLine(
            bomLineId, bomHeaderId, nextLineNumber,
            command.componentProductId, command.componentSku, command.componentName,
            command.componentKind,
            command.quantityPerFinishedUnit,
            command.scrapFactorPercent
        );

        if (cycleDetector.wouldCreateCycle(command.componentProductId, header.finishedProductId(), bomHeaderId)) {
            throw new BomCycleException(
                "Adding component " + command.componentSku + " to BOM " + bomHeaderId
                    + " would close a cycle: " + command.componentProductId
                    + " can already reach finished product " + header.finishedProductId()
            );
        }

        log.info("added bom_line {} (line_number={}) to bom_header {} for product {}",
            bomLineId, nextLineNumber, bomHeaderId, header.finishedProductSku());
        return bomLineId;
    }

    @Transactional
    public void removeLine(UUID bomLineId) {
        UUID bomHeaderId = bomEdits.findHeaderIdByLineId(bomLineId)
            .orElseThrow(() -> new BomLineNotFoundException(bomLineId));

        loadEditableHeader(bomHeaderId);

        if (!bomEdits.deleteLine(bomLineId)) {
            throw new BomLineNotFoundException(bomLineId);
        }
        log.info("removed bom_line {} from bom_header {}", bomLineId, bomHeaderId);
    }

    @Transactional
    public void activate(UUID bomHeaderId) {
        HeaderRow header = bomEdits.findHeader(bomHeaderId)
            .orElseThrow(() -> new BomNotFoundException(bomHeaderId));

        if (BOM_STATUS_ACTIVE.equals(header.status())) {
            return;
        }
        if (!BOM_STATUS_DRAFT.equals(header.status())) {
            throw new BomNotEditableException(
                "Cannot activate BOM " + bomHeaderId + " from status=" + header.status()
            );
        }

        if (bomEdits.countLines(bomHeaderId) == 0) {
            throw new BomNotEditableException(
                "Cannot activate BOM " + bomHeaderId + " with no lines"
            );
        }

        // The partial unique index uq_bom_active_per_product enforces "at most
        // one active per finished_product_id" — a competing active will surface
        // as a DataIntegrityViolationException here and the transaction rolls
        // back cleanly.
        bomEdits.markActive(bomHeaderId);

        List<UUID> componentProductIds = bomEdits.findComponentProductIds(bomHeaderId);
        for (UUID componentProductId : componentProductIds) {
            if (cycleDetector.wouldCreateCycle(componentProductId, header.finishedProductId(), null)) {
                throw new BomCycleException(
                    "Activating BOM " + bomHeaderId + " for product " + header.finishedProductSku()
                        + " would close a cycle through component " + componentProductId
                );
            }
        }

        log.info("activated bom_header {} for product {} ({} components)",
            bomHeaderId, header.finishedProductSku(), componentProductIds.size());

        // §2.8 Slice D: kick off the materialsCost rollup in the same
        // transaction as the activation. Walks parents recursively so a
        // multi-level activation cascade reaches everything in one go.
        rollup.recomputeViaBom(header.finishedProductId(), "bom_activated");
    }

    private HeaderRow loadEditableHeader(UUID bomHeaderId) {
        Optional<HeaderRow> header = bomEdits.findHeader(bomHeaderId);
        if (header.isEmpty()) {
            throw new BomNotFoundException(bomHeaderId);
        }
        if (BOM_STATUS_ACTIVE.equals(header.get().status())) {
            throw new BomNotEditableException(
                "BOM " + bomHeaderId + " is active; create a new draft to make changes"
            );
        }
        return header.get();
    }

}
