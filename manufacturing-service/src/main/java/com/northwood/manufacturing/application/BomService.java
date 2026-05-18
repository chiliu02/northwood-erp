package com.northwood.manufacturing.application;

import com.northwood.manufacturing.domain.Bom;
import com.northwood.manufacturing.domain.BomCycleDetector;
import com.northwood.manufacturing.domain.BomId;
import com.northwood.manufacturing.domain.BomLine;
import com.northwood.manufacturing.domain.BomLineId;
import com.northwood.manufacturing.domain.BomRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Editorial commands on the {@link Bom} aggregate. Thin orchestrator: load
 * the aggregate, invoke a mutator, save (which drains pendingEvents to the
 * outbox), run cross-aggregate post-conditions (cycle detection,
 * materialsCost rollup).
 *
 * <p>Three commands:
 *
 * <ul>
 *   <li>{@link #createDraft}: register a new {@link Bom} in {@code DRAFT}.</li>
 *   <li>{@link #addLine}: append a line to a draft. Pre-checks: component not
 *       discontinued (product-service contract). Post-check: the new edge set
 *       wouldn't close a cycle (walked via {@link BomCycleDetector}; on hit,
 *       the surrounding {@code @Transactional} rolls back the line insert).</li>
 *   <li>{@link #removeLine}: drop a line from a draft. Edges leaving the
 *       graph can't create cycles; no detector run.</li>
 *   <li>{@link #activate}: flip a draft to {@code ACTIVE}. Post-check: walk
 *       every component's subtree to make sure activation doesn't close a
 *       cycle through previously-inactive descendants. On success, kick off
 *       {@link MaterialsCostRollupService#recomputeViaBom} in the same
 *       transaction.</li>
 * </ul>
 *
 * <p>Promoted from a row-shaped service 2026-05-16 (§2.16). Previously the
 * state-machine invariants (status guards, line-number allocation, "at most
 * one active per product" via DB partial unique index) were spread across
 * this service and {@code BomEditRepository}; now they live on the
 * {@link Bom} aggregate, leaving the service to orchestrate cross-aggregate
 * concerns.
 */
@Service
public class BomService {

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

    /**
     * Application-layer wrapper around the domain
     * {@link Bom.BomNotEditableException}. Controllers catch this (HTTP 409)
     * instead of importing the domain exception type directly.
     */
    public static class BomNotEditableException extends RuntimeException {
        public BomNotEditableException(String message, Throwable cause) {
            super(message, cause);
        }

        public BomNotEditableException(String message) {
            super(message);
        }
    }

    /**
     * Application-layer wrapper around the domain {@link Bom.BomCycleException}
     * plus the post-save cycle-detection findings. Controllers catch this
     * (HTTP 409) instead of importing the domain exception type directly.
     */
    public static class BomCycleException extends RuntimeException {
        public BomCycleException(String message, Throwable cause) {
            super(message, cause);
        }

        public BomCycleException(String message) {
            super(message);
        }
    }

    /**
     * §1.4 B.3: thrown when {@link #addLine} is called with a
     * {@code componentProductId} that product-service has already discontinued.
     * Mirrors purchasing's {@code ProductDiscontinuedException} shape — a
     * planner shouldn't be allowed to author a draft BOM that names a
     * retired SKU, even before activation.
     */
    public static class BomComponentDiscontinuedException extends RuntimeException {
        public BomComponentDiscontinuedException(UUID componentProductId, String componentSku) {
            super("Component product " + componentSku + " (" + componentProductId
                + ") has been discontinued by product-service; cannot add to a BOM");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(BomService.class);

    private final BomRepository boms;
    private final BomCycleDetector cycleDetector;
    private final MaterialsCostRollupService rollup;
    private final DiscontinuedProductLookup discontinuedProducts;

    public BomService(
        BomRepository boms,
        BomCycleDetector cycleDetector,
        MaterialsCostRollupService rollup,
        DiscontinuedProductLookup discontinuedProducts
    ) {
        this.boms = boms;
        this.cycleDetector = cycleDetector;
        this.rollup = rollup;
        this.discontinuedProducts = discontinuedProducts;
    }

    /**
     * Create a new BOM draft. Returns the new {@code bom_header_id} so
     * subsequent {@link #addLine} / {@link #activate} calls can target it.
     */
    @Transactional
    public UUID createDraft(CreateBomDraftCommand command) {
        if (command.finishedProductId() == null) {
            throw new IllegalArgumentException("finishedProductId required");
        }
        String version = command.version() == null || command.version().isBlank()
            ? "1" : command.version();
        Bom bom;
        try {
            bom = Bom.draft(
                command.finishedProductId(),
                command.finishedProductSku(),
                command.finishedProductName(),
                version
            );
        } catch (NullPointerException e) {
            // Aggregate guards use Objects.requireNonNull which throws NPE with a
            // message naming the offending arg; rewrap as IllegalArgumentException
            // for an HTTP 400-shaped error rather than a 500.
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        boms.save(bom);
        log.info("created bom_header {} (draft) for product {} version {}",
            bom.id().value(), bom.finishedProductSku(), bom.version());
        return bom.id().value();
    }

    @Transactional
    public UUID addLine(UUID bomHeaderId, AddLineCommand command) {
        // §1.4 B.3: reject discontinued components upfront. Mirrors
        // purchasing's PR-entry gate; prevents authoring a BOM that names
        // a retired SKU before the planner gets to activation.
        if (discontinuedProducts.isDiscontinued(command.componentProductId())) {
            throw new BomComponentDiscontinuedException(command.componentProductId(), command.componentSku());
        }
        Bom bom = boms.findById(BomId.of(bomHeaderId))
            .orElseThrow(() -> new BomNotFoundException(bomHeaderId));

        BomLine line;
        try {
            line = bom.addLine(new BomLine.Spec(
                command.componentProductId(),
                command.componentSku(),
                command.componentName(),
                command.componentKind(),
                command.quantityPerFinishedUnit(),
                command.scrapFactorPercent()
            ));
        } catch (Bom.BomCycleException e) {
            throw new BomCycleException(e.getMessage(), e);
        } catch (Bom.BomNotEditableException e) {
            throw new BomNotEditableException(e.getMessage(), e);
        }
        boms.save(bom);
        // Post-save cycle check: detector walks the DB graph (now including this
        // line) and rolls back the surrounding @Transactional on a positive
        // finding. See Bom's class Javadoc for why this lives one layer up
        // from the aggregate.
        if (cycleDetector.wouldCreateCycle(command.componentProductId(), bom.finishedProductId(), bomHeaderId)) {
            throw new BomCycleException(
                "Adding component " + command.componentSku() + " to BOM " + bomHeaderId
                    + " would close a cycle: " + command.componentProductId()
                    + " can already reach finished product " + bom.finishedProductId()
            );
        }
        log.info("added bom_line {} (line_number={}) to bom_header {} for product {}",
            line.id().value(), line.lineNumber(), bomHeaderId, bom.finishedProductSku());
        return line.id().value();
    }

    @Transactional
    public void removeLine(UUID bomLineId) {
        BomId bomId = boms.findBomIdByLineId(BomLineId.of(bomLineId))
            .orElseThrow(() -> new BomLineNotFoundException(bomLineId));
        Bom bom = boms.findById(bomId)
            .orElseThrow(() -> new BomNotFoundException(bomId.value()));
        boolean removed;
        try {
            removed = bom.removeLine(BomLineId.of(bomLineId));
        } catch (Bom.BomNotEditableException e) {
            throw new BomNotEditableException(e.getMessage(), e);
        }
        if (!removed) {
            throw new BomLineNotFoundException(bomLineId);
        }
        boms.save(bom);
        log.info("removed bom_line {} from bom_header {}", bomLineId, bomId.value());
    }

    @Transactional
    public void activate(UUID bomHeaderId) {
        Bom bom = boms.findById(BomId.of(bomHeaderId))
            .orElseThrow(() -> new BomNotFoundException(bomHeaderId));
        Bom.Status before = bom.status();
        try {
            bom.activate();
        } catch (Bom.BomNotEditableException e) {
            throw new BomNotEditableException(e.getMessage(), e);
        }
        if (before == Bom.Status.ACTIVE) {
            // No-op (aggregate returned without state change); skip save +
            // post-checks + rollup so we don't recompute on every redundant call.
            return;
        }
        boms.save(bom);
        // Post-save cycle check: walk every component's subtree to ensure
        // activation doesn't close a cycle through previously-inactive
        // descendants. On positive finding, the @Transactional rolls back
        // the activation.
        for (UUID componentProductId : bom.componentProductIds()) {
            if (cycleDetector.wouldCreateCycle(componentProductId, bom.finishedProductId(), null)) {
                throw new BomCycleException(
                    "Activating BOM " + bomHeaderId + " for product " + bom.finishedProductSku()
                        + " would close a cycle through component " + componentProductId
                );
            }
        }
        log.info("activated bom_header {} for product {} ({} components)",
            bomHeaderId, bom.finishedProductSku(), bom.lineCount());
        // §2.8 Slice D: kick off the materialsCost rollup in the same
        // transaction as the activation. Walks parents recursively so a
        // multi-level activation cascade reaches everything in one go.
        rollup.recomputeViaBom(bom.finishedProductId(), "bom_activated");
    }
}
