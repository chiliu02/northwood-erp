package com.northwood.testharness.replenishment;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.manufacturing.domain.events.ReplenishmentDispatched;
import com.northwood.manufacturing.domain.events.WorkOrderCreated;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.inmemory.manufacturing.InMemoryBomLookup;
import com.northwood.testharness.kits.InventoryTestKit;
import com.northwood.testharness.kits.ManufacturingTestKit;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Manufactured-path replenishment, end-to-end.
 *
 * <p>Drives the full replenishment lifecycle for a finished-good replenishment:
 * <ol>
 *   <li>Inventory's reorder-point detection trigger raises a
 *       {@code ReplenishmentRequest(status=requested,
 *       targetService=manufacturing, reason=reorder_point_breach)} and emits
 *       {@code inventory.ReplenishmentRequested}.</li>
 *   <li>Manufacturing's {@code ReplenishmentRequestedHandler} releases a
 *       stock work order via {@code WorkOrder.releaseForReplenishment(...)},
 *       which emits both {@code WorkOrderCreated} (with
 *       {@code replenishmentRequestId} populated) and
 *       {@code manufacturing.ReplenishmentDispatched}.</li>
 *   <li>Inventory's {@code ManufacturingReplenishmentDispatchedHandler}
 *       consumes the dispatch event and flips the request to
 *       {@code dispatched}.</li>
 *   <li>We inject {@code manufacturing.WorkOrderManufacturingCompleted}
 *       (the WO would otherwise complete via the operation-completion flow,
 *       out of scope for this scenario).</li>
 *   <li>Inventory's existing
 *       {@code WorkOrderManufacturingCompletedHandler} bumps FG stock, finds
 *       the open replenishment by
 *       {@code dispatched_aggregate_id = workOrderId}, and calls
 *       {@code markFulfilled()} — emitting
 *       {@code inventory.ReplenishmentFulfilled}.</li>
 * </ol>
 */
class StockReplenishmentManufacturedPathTest {

    @Test
    void reorder_point_breach_releases_wo_and_fulfils_on_completion() throws Exception {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        ManufacturingTestKit mfg = new ManufacturingTestKit(bus, json);
        InventoryTestKit inv = new InventoryTestKit(bus, json);

        // Seed: a manufactured FG with a 1-raw-component BOM + single-op routing.
        UUID fgProductId = UUID.randomUUID();
        UUID rawProductId = UUID.randomUUID();
        UUID bomHeaderId = UUID.randomUUID();

        // Make-vs-buy: manufactured (inventory's local snapshot drives routing,
        // mfg's snapshot drives the dispatcher's filter).
        inv.productReplenishment.put(fgProductId, /*purchased=*/false, /*manufactured=*/true);
        mfg.replenishment.put(fgProductId, /*purchased=*/false, /*manufactured=*/true);

        mfg.bomLookup.put(fgProductId, bomHeaderId,
            InMemoryBomLookup.rawLine(rawProductId, "RM-001", "Raw Material 1",
                new BigDecimal("1"), BigDecimal.ZERO));
        mfg.bomLookup.putIdentity(fgProductId, "FG-RPL-001", "Replenishment FG 001");
        mfg.routings.putSingleOp(fgProductId);

        // Reorder policy: reorder_point=5, reorder_quantity=10. Pre-decrement
        // balance set below the threshold so the next detection check fires.
        inv.reorderPolicies.put(fgProductId, new BigDecimal("5"), new BigDecimal("10"));
        inv.stockBalances.seedOnHand(InventoryTestKit.DEFAULT_WAREHOUSE_ID, fgProductId, new BigDecimal("3"));

        // ───────────────────────────────────────────────────────────────────
        // Step 1: trigger the detection. This is the entry point the
        // ShipmentService / StockAdjustmentService would call in production
        // after each on-hand decrement.
        inv.replenishmentDetection.checkAfterOnHandDecrement(
            InventoryTestKit.DEFAULT_WAREHOUSE_ID, fgProductId
        );

        // Assert: one ReplenishmentRequest created in 'requested', routed to
        // manufacturing, with reason=reorder_point_breach and quantity=10.
        List<ReplenishmentRequest> requests = inv.replenishmentRequests.all();
        assertThat(requests).hasSize(1);
        ReplenishmentRequest r = requests.get(0);
        UUID replenishmentRequestId = r.id().value();
        assertThat(r.status()).isEqualTo(ReplenishmentRequest.Status.REQUESTED);
        assertThat(r.targetService()).isEqualTo(ReplenishmentRequest.TargetService.MANUFACTURING);
        assertThat(r.reason()).isEqualTo(ReplenishmentRequest.Reason.REORDER_POINT_BREACH);
        assertThat(r.requestedQuantity()).isEqualByComparingTo("10");

        // Drain bus so mfg consumes ReplenishmentRequested → releases WO → emits
        // WorkOrderCreated + ReplenishmentDispatched. Then inv consumes
        // ReplenishmentDispatched → flips request to 'dispatched'.
        bus.drain();

        // Assert: WO was created with replenishmentRequestId populated.
        WorkOrderCreated workOrderCreated = (WorkOrderCreated) mfg.outbox.all().stream()
            .filter(o -> WorkOrderCreated.EVENT_TYPE.equals(o.getEventType()))
            .findFirst()
            .map(o -> {
                try { return json.readValue(o.getPayload(), WorkOrderCreated.class); }
                catch (Exception e) { throw new RuntimeException(e); }
            })
            .orElseThrow();
        assertThat(workOrderCreated.replenishmentRequestId()).isEqualTo(replenishmentRequestId);
        UUID workOrderId = workOrderCreated.aggregateId();

        // Assert: manufacturing.ReplenishmentDispatched was emitted, and inv
        // flipped the request to 'dispatched'.
        assertThat(mfg.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(ReplenishmentDispatched.EVENT_TYPE);

        ReplenishmentRequest afterDispatch = inv.replenishmentRequests
            .findById(r.id())
            .orElseThrow();
        assertThat(afterDispatch.status()).isEqualTo(ReplenishmentRequest.Status.DISPATCHED);
        assertThat(afterDispatch.dispatchedAggregateKind())
            .isEqualTo(ReplenishmentRequest.DispatchedAggregateKind.WORK_ORDER);
        assertThat(afterDispatch.dispatchedAggregateId()).isEqualTo(workOrderId);

        // ───────────────────────────────────────────────────────────────────
        // Step 2: simulate the WO completing. In production this flows through
        // operation-completion + saga-worker; the harness scenario shortcuts
        // by emitting WorkOrderManufacturingCompleted directly. inv's existing
        // handler bumps FG stock and (Slice E extension) finds + fulfils the
        // open replenishment whose dispatched_aggregate_id matches.
        UUID completionEventId = UUID.randomUUID();
        WorkOrderManufacturingCompleted completion = new WorkOrderManufacturingCompleted(
            completionEventId, workOrderId, workOrderCreated.workOrderNumber(),
            /*salesOrderHeaderId=*/null, /*salesOrderLineId=*/null,
            /*parentWorkOrderId=*/null,
            fgProductId, "FG-RPL-001", new BigDecimal("10"), Instant.now()
        );
        mfg.outbox.appendPending(OutboxRow.pending(
            completionEventId, "WorkOrder", workOrderId,
            WorkOrderManufacturingCompleted.EVENT_TYPE, 1,
            json.writeValueAsString(completion),
            null, null, null, null
        ));
        bus.drain();

        // Assert: replenishment is fulfilled.
        ReplenishmentRequest afterFulfil = inv.replenishmentRequests
            .findById(r.id())
            .orElseThrow();
        assertThat(afterFulfil.status()).isEqualTo(ReplenishmentRequest.Status.FULFILLED);
        assertThat(afterFulfil.fulfilledAt()).isNotNull();

        // Assert: ReplenishmentFulfilled was emitted to inventory's outbox.
        assertThat(inv.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(
                ReplenishmentRequested.EVENT_TYPE,
                ReplenishmentFulfilled.EVENT_TYPE
            );
    }
}
