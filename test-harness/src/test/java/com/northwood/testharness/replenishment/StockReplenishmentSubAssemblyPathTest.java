package com.northwood.testharness.replenishment;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.saga.WorkOrderSaga;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.inmemory.manufacturing.InMemoryBomLookup;
import com.northwood.testharness.kits.InventoryTestKit;
import com.northwood.testharness.kits.ManufacturingTestKit;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Multi-level (sub-assembly) make-to-stock replenishment.
 *
 * <p>A reorder-point breach on a finished good whose BOM contains a
 * sub-assembly now releases BOTH the top-level replenishment work order and a
 * child work order for the sub-assembly (the recursion ported from the
 * make-to-order path), and seeds a {@code work_order_saga} per work order
 * with a null sales-order pair (make-to-stock). Before this change,
 * {@code releaseForReplenishment} skipped sub-assemblies and seeded no saga.
 *
 * <p>The release recursion runs synchronously inside the handler, so a single
 * {@code bus.drain()} is enough — no saga-worker tick required to observe both
 * work orders + both sagas.
 */
class StockReplenishmentSubAssemblyPathTest {

    @Test
    void reorder_point_breach_recurses_sub_assemblies_and_seeds_sagas() {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        ManufacturingTestKit mfg = new ManufacturingTestKit(bus, json);
        InventoryTestKit inv = new InventoryTestKit(bus, json);

        UUID fgId = UUID.randomUUID();
        UUID subAId = UUID.randomUUID();
        UUID woodId = UUID.randomUUID();
        UUID steelId = UUID.randomUUID();

        // Make-vs-buy: FG + SubA manufactured (inventory's snapshot drives the
        // routing decision; mfg's drives the dispatcher filter).
        inv.productReplenishment.put(fgId, /*purchased=*/false, /*manufactured=*/true);
        mfg.replenishment.put(fgId, false, true);
        mfg.replenishment.put(subAId, false, true);

        // Two-level BOM: FG → SubA (sub_assembly) + Wood (raw); SubA → Steel (raw).
        mfg.bomLookup.put(fgId, UUID.randomUUID(),
            InMemoryBomLookup.subAssemblyLine(subAId, "SUB-A", "Sub-Assembly A",
                new BigDecimal("1"), BigDecimal.ZERO),
            InMemoryBomLookup.rawLine(woodId, "WOOD", "Wood",
                new BigDecimal("2"), BigDecimal.ZERO));
        mfg.bomLookup.put(subAId, UUID.randomUUID(),
            InMemoryBomLookup.rawLine(steelId, "STEEL", "Steel",
                new BigDecimal("3"), BigDecimal.ZERO));
        mfg.bomLookup.putIdentity(fgId, "FG-RPL-SUB", "Replenishment FG w/ Sub-Assembly");
        mfg.routings.putSingleOp(fgId);
        mfg.routings.putSingleOp(subAId);

        // Reorder policy + below-threshold balance so detection fires.
        inv.reorderPolicies.put(fgId, new BigDecimal("5"), new BigDecimal("10"));
        inv.stockBalances.seedOnHand(InventoryTestKit.DEFAULT_WAREHOUSE_ID, fgId, new BigDecimal("3"));

        inv.replenishmentDetection.checkAfterOnHandDecrement(
            InventoryTestKit.DEFAULT_WAREHOUSE_ID, fgId
        );
        UUID replenishmentRequestId = inv.replenishmentRequests.all().get(0).id().value();

        // Drain: mfg's ReplenishmentRequestedHandler releases the WO tree
        // synchronously (recursion is inside releaseForReplenishment).
        bus.drain();

        // Two sagas seeded — one per WO — both make-to-stock (null sales-order),
        // both parked at work_order_created (no worker tick yet).
        List<WorkOrderSaga> sagas = mfg.sagas.all();
        assertThat(sagas).hasSize(2);
        assertThat(sagas).allSatisfy(s -> {
            assertThat(s.salesOrderHeaderId()).isNull();
            assertThat(s.salesOrderLineId()).isNull();
            assertThat(s.workOrderId()).isNotNull();
            assertThat(s.state()).isEqualTo(WorkOrderSaga.WORK_ORDER_CREATED);
        });

        // Identify the FG (root) and SubA (child) work orders via the sagas.
        WorkOrder fgWo = null;
        WorkOrder subAWo = null;
        for (WorkOrderSaga s : sagas) {
            WorkOrder wo = mfg.workOrders.findById(WorkOrderId.of(s.workOrderId())).orElseThrow();
            if (wo.parentWorkOrderId() == null) {
                fgWo = wo;
            } else {
                subAWo = wo;
            }
        }
        assertThat(fgWo).as("root replenishment WO").isNotNull();
        assertThat(subAWo).as("sub-assembly child WO").isNotNull();

        // Root carries the replenishment request; child carries only its parent.
        assertThat(fgWo.replenishmentRequestId()).isEqualTo(replenishmentRequestId);
        assertThat(fgWo.finishedProductId()).isEqualTo(fgId);
        assertThat(subAWo.replenishmentRequestId()).isNull();
        assertThat(subAWo.parentWorkOrderId()).isEqualTo(fgWo.id().value());
        assertThat(subAWo.finishedProductId()).isEqualTo(subAId);
    }
}
