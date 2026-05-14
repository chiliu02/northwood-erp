package com.northwood.testharness.m2o;

import com.northwood.manufacturing.domain.events.SubAssembliesConsumed;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.manufacturing.application.dto.CompleteOperationCommand;
import com.northwood.sales.domain.events.ManufacturingRequested;
import com.northwood.sales.domain.events.ManufacturingRequested.RequestedLine;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.saga.MakeToOrderSaga;
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
 * §2.5.1 Slice G — sub-assembly recursion E2E. Two-level BoM:
 * {@code FG → SubA (sub_assembly) + Wood (raw)} and {@code SubA → Steel (raw)}.
 *
 * <p>Validates:
 * <ul>
 *   <li>Release walks the sub-assembly graph and spawns a child WO + saga
 *       per sub_assembly entry.</li>
 *   <li>Both WOs reserve raw materials independently.</li>
 *   <li>Child WO completion bumps WIP (not FG stock) — parentWorkOrderId
 *       is non-null.</li>
 *   <li>Parent WO completion only fires when its own ops are done AND no
 *       child remains unfinished — child completes first, parent finishes
 *       second.</li>
 *   <li>Parent completion emits SubAssembliesConsumed; inventory decrements
 *       WIP for the consumed child product.</li>
 * </ul>
 */
class SubAssemblyRecursionTest {

    @Test
    void parent_wo_waits_for_child_then_consumes_wip() throws Exception {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        ManufacturingTestKit mfg = new ManufacturingTestKit(bus, json);
        InventoryTestKit inv = new InventoryTestKit(bus, json);

        UUID fgId = UUID.randomUUID();
        UUID subAId = UUID.randomUUID();
        UUID woodId = UUID.randomUUID();
        UUID steelId = UUID.randomUUID();

        mfg.replenishment.put(fgId, false, true);
        mfg.replenishment.put(subAId, false, true);
        mfg.replenishment.put(woodId, true, false);
        mfg.replenishment.put(steelId, true, false);

        mfg.boms.put(fgId, UUID.randomUUID(),
            InMemoryBomLookup.subAssemblyLine(subAId, "SUB-A", "Sub-Assembly A",
                new BigDecimal("1"), BigDecimal.ZERO),
            InMemoryBomLookup.rawLine(woodId, "WOOD", "Wood",
                new BigDecimal("2"), BigDecimal.ZERO));
        mfg.boms.put(subAId, UUID.randomUUID(),
            InMemoryBomLookup.rawLine(steelId, "STEEL", "Steel",
                new BigDecimal("3"), BigDecimal.ZERO));

        mfg.routings.putSingleOp(fgId);
        mfg.routings.putSingleOp(subAId);

        inv.seedStock(woodId, new BigDecimal("100"));
        inv.seedStock(steelId, new BigDecimal("100"));

        // Step 1: kick off via injected ManufacturingRequested.
        UUID salesOrderHeaderId = UUID.randomUUID();
        UUID salesOrderLineId = UUID.randomUUID();
        ManufacturingRequested payload = new ManufacturingRequested(
            UUID.randomUUID(), salesOrderHeaderId, salesOrderHeaderId,
            List.of(new RequestedLine(
                salesOrderLineId, 10, fgId, "FG-100", "Top Finished Good",
                new BigDecimal("4")
            )),
            Instant.now()
        );
        mfg.outbox.appendPending(OutboxRow.pending(
            payload.eventId(), "SalesOrder", salesOrderHeaderId,
            ManufacturingRequested.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null
        ));

        bus.drain();

        // Step 2: release. Worker tick spawns FG WO + SubA child WO + SubA child saga.
        mfg.advanceSagaWorker();

        List<MakeToOrderSaga> sagas = mfg.sagas.all();
        assertThat(sagas).hasSize(2);
        MakeToOrderSaga fgSaga = sagas.stream()
            .filter(s -> s.workOrderId() != null && mfg.workOrders.findById(WorkOrderId.of(s.workOrderId())).orElseThrow().parentWorkOrderId() == null)
            .findFirst().orElseThrow();
        MakeToOrderSaga subASaga = sagas.stream()
            .filter(s -> !s.sagaId().equals(fgSaga.sagaId()))
            .findFirst().orElseThrow();

        UUID fgWoId = fgSaga.workOrderId();
        UUID subAWoId = subASaga.workOrderId();

        // Step 3: drive worker again — both sagas at work_order_created emit
        // RawMaterialReservationRequested.
        mfg.advanceSagaWorker();

        // Step 4: drain → inventory reserves both, emits RawMaterialsReserved.
        // Manufacturing's RawMaterialsReservedHandler advances each saga to
        // raw_materials_reserved.
        bus.drain();

        assertThat(mfg.sagas.findBySagaId(fgSaga.sagaId()).orElseThrow().state())
            .isEqualTo(MakeToOrderSaga.RAW_MATERIALS_RESERVED);
        assertThat(mfg.sagas.findBySagaId(subASaga.sagaId()).orElseThrow().state())
            .isEqualTo(MakeToOrderSaga.RAW_MATERIALS_RESERVED);

        // Step 5: complete operation on the SubA child WO (the leaf).
        WorkOrder subAWo = mfg.workOrders.findById(WorkOrderId.of(subAWoId)).orElseThrow();
        int subAOpSeq = subAWo.operations().get(0).operationSequence();
        mfg.operationService.completeOperation(new CompleteOperationCommand(
            subAWoId, subAOpSeq, BigDecimal.valueOf(45)
        ));

        bus.drain();

        // SubA saga completed; FG saga still in raw_materials_reserved (FG's
        // own ops haven't been done yet).
        assertThat(mfg.sagas.findBySagaId(subASaga.sagaId()).orElseThrow().state())
            .isEqualTo(MakeToOrderSaga.COMPLETED);
        assertThat(mfg.sagas.findBySagaId(fgSaga.sagaId()).orElseThrow().state())
            .isEqualTo(MakeToOrderSaga.RAW_MATERIALS_RESERVED);

        // WIP for SubA was bumped (child completion goes to WIP, not FG stock).
        assertThat(inv.wipBalances.totalFor(subAId))
            .as("SubA WIP bumped on child completion")
            .isEqualByComparingTo(new BigDecimal("4"));

        // Step 6: complete operation on FG WO. Cascade gates parent on children
        // already done — siblings (none other than SubA) are all done, so FG
        // can complete.
        WorkOrder fgWo = mfg.workOrders.findById(WorkOrderId.of(fgWoId)).orElseThrow();
        int fgOpSeq = fgWo.operations().get(0).operationSequence();
        mfg.operationService.completeOperation(new CompleteOperationCommand(
            fgWoId, fgOpSeq, BigDecimal.valueOf(60)
        ));

        bus.drain();

        // FG saga completed. Inventory: SubAssembliesConsumed emission
        // decrements WIP for SubA back toward zero.
        assertThat(mfg.sagas.findBySagaId(fgSaga.sagaId()).orElseThrow().state())
            .isEqualTo(MakeToOrderSaga.COMPLETED);
        assertThat(mfg.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(SubAssembliesConsumed.EVENT_TYPE);
        assertThat(inv.wipBalances.totalFor(subAId))
            .as("SubA WIP decremented after parent consumed it")
            .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
