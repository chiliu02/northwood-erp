package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.REJECTED;

import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.manufacturing.domain.events.ManufacturingDispatched;
import com.northwood.manufacturing.domain.events.ManufacturingDispatched.LineOutcome;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager.PurchasingDivergence;
import com.northwood.sales.application.saga.SalesOrderLineSnapshotPort;
import com.northwood.sales.application.saga.SalesOrderLineSnapshotPort.LineSnapshot;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderRepository;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.sales.domain.events.SalesOrderPurchasingRequested;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code manufacturing.ManufacturingDispatched}. Counts
 * accepted lines, asks the manager to apply the dispatch outcome, and on
 * any rejection (§4.2 closure, 2026-05-15) flips the order header to
 * {@code 'rejected'} + emits {@code sales.SalesOrderCancellationRequested}
 * so inventory releases any partial reservation and manufacturing cancels
 * the make-to-order sagas it already started for the accepted lines.
 *
 * <p>Pre-§4.2 closure: this handler only flipped the order to
 * {@code 'rejected'} when ALL lines were rejected (the saga manager
 * returned {@code rejected} only on {@code !anyAccepted}).
 * Partial rejection silently dropped the rejected lines and proceeded
 * with the accepted ones — order ended up with {@code total_amount}
 * reflecting the originally-ordered total while only the accepted lines
 * shipped. The new policy: ANY rejection rejects the whole order.
 */
@Component
public class ManufacturingDispatchedHandler extends AbstractInboxHandler<ManufacturingDispatched> {

    public static final String CONSUMER_NAME = "sales.fulfilment-saga.manufacturing-dispatched";

    /**
     * §2.36: outcome string emitted by manufacturing's
     * {@code ManufacturingRequestedHandler} when a line's SKU has
     * {@code is_manufactured = false} (purchased-only). Used as the discriminator
     * between "the order genuinely can't be made" ({@code rejected_no_bom}
     * → terminal rejection) and "the order needs the purchasing branch"
     * ({@code rejected_not_manufactured} → reroute to {@code purchasing_requested}).
     */
    private static final String OUTCOME_REJECTED_NOT_MANUFACTURED = "rejected_not_manufactured";
    private static final String OUTCOME_ACCEPTED = "accepted";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderHeaderStatusProjection statusProjection;
    private final SalesOrderRepository salesOrders;
    private final SalesOrderLineSnapshotPort lineSnapshots;
    private final OutboxAppender outbox;

    public ManufacturingDispatchedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderHeaderStatusProjection statusProjection,
        SalesOrderRepository salesOrders,
        SalesOrderLineSnapshotPort lineSnapshots,
        OutboxAppender outbox,
        ObjectMapper json
    ) {
        super(inbox, json, ManufacturingDispatched.class, ManufacturingDispatched.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.statusProjection = statusProjection;
        this.salesOrders = salesOrders;
        this.lineSnapshots = lineSnapshots;
        this.outbox = outbox;
    }

    @Override
    protected void apply(ManufacturingDispatched payload, EventEnvelope envelope) {
        int acceptedCount = (int) payload.lines().stream()
            .filter(l -> OUTCOME_ACCEPTED.equals(l.outcome()))
            .count();
        int totalLines = payload.lines().size();

        // §2.36 reroute gate: zero accepted AND every rejected outcome is
        // rejected_not_manufactured (purchased-only) → instead of terminal
        // REJECTED, divert to the new purchasing_requested branch and emit
        // sales.SalesOrderPurchasingRequested so inventory raises per-line
        // ReplenishmentRequest(s). Mixed cases (some accepted, or any
        // rejected_no_bom) fall through to the existing §4.2 full-rejection
        // path — restoring symmetry for those is a §2.36 follow-up.
        if (acceptedCount == 0 && totalLines > 0 && allRejectedNotManufactured(payload.lines())) {
            // §2.36 Slice E: pass the outstanding line-ids so the saga can
            // stash them on saga.data — the fan-in handler looks them up to
            // know which ReplenishmentFulfilled events are "addressed to us".
            java.util.Set<UUID> outstandingLineIds = new java.util.LinkedHashSet<>();
            for (LineOutcome rejected : payload.lines()) {
                outstandingLineIds.add(rejected.salesOrderLineId());
            }
            Optional<PurchasingDivergence> divergence = sagaManager
                .applyManufacturingDispatchedReroutingToPurchasing(payload.salesOrderHeaderId(), outstandingLineIds);
            if (divergence.isPresent()) {
                emitPurchasingRequested(payload, divergence.get());
                log.info("[{}] sales_order={} rerouted to purchasing_requested ({} purchased-only line(s) for replenishment)",
                    CONSUMER_NAME, payload.salesOrderHeaderId(), totalLines);
                return;
            }
            // Manager declined the transition (saga not in MANUFACTURING_REQUESTED,
            // or no shortage on saga.data). Fall through to the legacy path so
            // the existing rejection semantics still fire if applicable.
        }

        String newState = sagaManager.applyManufacturingDispatched(
            payload.salesOrderHeaderId(), acceptedCount, totalLines
        );
        if (REJECTED.equals(newState)) {
            statusProjection.markStatus(payload.salesOrderHeaderId(), SalesOrder.Status.REJECTED);
            String reason = buildRejectionReason(payload, acceptedCount, totalLines);
            emitCancellationRequest(payload.salesOrderHeaderId(), reason);
            log.info("[{}] sales_order={} rejected ({} accepted, {} rejected); compensation requested",
                CONSUMER_NAME, payload.salesOrderHeaderId(),
                acceptedCount, totalLines - acceptedCount);
        }
    }

    private static boolean allRejectedNotManufactured(List<LineOutcome> lines) {
        for (LineOutcome l : lines) {
            if (!OUTCOME_REJECTED_NOT_MANUFACTURED.equals(l.outcome())) {
                return false;
            }
        }
        return true;
    }

    /**
     * §2.36: build {@code sales.SalesOrderPurchasingRequested} from the
     * mfg-dispatched payload (line identities) + the saga's stashed shortage
     * map (per-line shortage quantities, returned by the manager). Looks up
     * SKU/name from the line-snapshot table; the mfg-dispatched payload's
     * {@code productSku} is also available but the snapshot is authoritative
     * (carries the full identity tuple the inventory handler will need).
     */
    private void emitPurchasingRequested(
        ManufacturingDispatched payload,
        PurchasingDivergence divergence
    ) {
        UUID salesOrderHeaderId = payload.salesOrderHeaderId();
        Map<Integer, BigDecimal> shortageByLineNumber = divergence.shortageByLineNumber();
        Map<UUID, LineSnapshot> snapshotsById = new HashMap<>();
        for (LineSnapshot s : lineSnapshots.findLines(salesOrderHeaderId)) {
            snapshotsById.put(s.salesOrderLineId(), s);
        }

        List<SalesOrderPurchasingRequested.RequestedLine> requestedLines = new ArrayList<>();
        for (LineOutcome rejected : payload.lines()) {
            BigDecimal shortage = shortageByLineNumber.get(rejected.lineNumber());
            if (shortage == null || shortage.signum() <= 0) {
                continue;
            }
            LineSnapshot snap = snapshotsById.get(rejected.salesOrderLineId());
            String sku = snap != null ? snap.productSku() : rejected.productSku();
            String name = snap != null ? snap.productName() : rejected.productSku();
            requestedLines.add(new SalesOrderPurchasingRequested.RequestedLine(
                rejected.salesOrderLineId(),
                rejected.lineNumber(),
                rejected.productId(),
                sku,
                name,
                shortage
            ));
        }

        if (requestedLines.isEmpty()) {
            log.warn("[{}] sales_order={} divergence returned no lines after intersect with mfg-dispatched payload; skipping {} emission",
                CONSUMER_NAME, salesOrderHeaderId, SalesOrderPurchasingRequested.EVENT_TYPE);
            return;
        }

        outbox.append(new SalesOrderPurchasingRequested(
            UUID.randomUUID(),
            salesOrderHeaderId,
            salesOrderHeaderId,
            WarehouseCodes.MAIN,
            requestedLines,
            Instant.now()
        ), SalesOrderFulfilmentSaga.AGGREGATE_TYPE);
    }

    /**
     * §4.2 closure: emit {@code sales.SalesOrderCancellationRequested} for a
     * system-driven rejection (any rejected dispatch line) without going
     * through {@link SalesOrder#cancel(String)}. Downstream inventory +
     * manufacturing handlers consume it to release the partial stock
     * reservation and cancel any already-started make-to-order sagas.
     *
     * <p><b>Silent-fallback contract.</b> Loads the order to populate
     * {@code orderNumber} + {@code customerId} on the payload. If the order
     * can't be loaded — shouldn't happen, since we're inside the same
     * transaction that just observed {@code ManufacturingDispatched} for an
     * existing order — we log WARN and skip the emission rather than throw;
     * the sales saga has already transitioned to
     * {@code rejected} (terminal), so even without downstream
     * compensation the saga itself is in a sensible state. (Inlined from the
     * former {@code SalesOrderCompensationEmitter.emitCancellationRequest},
     * which had a single caller.)
     */
    private void emitCancellationRequest(UUID salesOrderHeaderId, String reason) {
        SalesOrder order = salesOrders.findById(SalesOrderId.of(salesOrderHeaderId)).orElse(null);
        if (order == null) {
            log.warn("emitCancellationRequest sales_order={} could not load SalesOrder; skipping emission. "
                + "Downstream compensation (stock release + work-order cancellation) will NOT fire.",
                salesOrderHeaderId);
            return;
        }
        outbox.append(new SalesOrderCancellationRequested(
            UUID.randomUUID(),
            salesOrderHeaderId,
            order.orderNumber(),
            order.customerId(),
            reason,
            Instant.now()
        ), SalesOrder.AGGREGATE_TYPE);
    }

    private static String buildRejectionReason(ManufacturingDispatched payload, int accepted, int total) {
        String rejectedSkus = payload.lines().stream()
            .filter(l -> !"accepted".equals(l.outcome()))
            .map(l -> l.productSku() + " (" + l.outcome() + ")")
            .collect(Collectors.joining(", "));
        return accepted == 0
            ? "All " + total + " line(s) rejected by manufacturing dispatch: " + rejectedSkus
            : (total - accepted) + " of " + total + " line(s) rejected: " + rejectedSkus
                + "; order cannot be partially fulfilled.";
    }
}
