package com.northwood.testharness.m2o;

import com.northwood.inventory.domain.InventoryAggregateTypes;
import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.manufacturing.domain.events.RawMaterialShortageDetected;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.sales.domain.events.ManufacturingRequested;
import com.northwood.sales.domain.events.ManufacturingRequested.RequestedLine;
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
 * §2.5.1 Slice E — make-to-order shortage path. Single variant: shortage
 * detected on first reservation attempt → goods receipt arrives covering
 * the shortage → saga un-parks → reserve succeeds → saga at
 * {@code raw_materials_reserved}.
 *
 * <p>Drives the full make-to-order flow through the manufacturing kit's
 * saga worker and the inventory kit's reservation handler. The
 * sales-side {@code ManufacturingRequested} is injected directly into a
 * shared outbox to skip the sales saga (Slice D covers that leg).
 */
class MakeToOrderShortagePathTest {

    @Test
    void shortage_then_receipt_unparks_and_reserves_successfully() throws Exception {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        ManufacturingTestKit mfg = new ManufacturingTestKit(bus, json);
        InventoryTestKit inv = new InventoryTestKit(bus, json);

        // Seed: FG (manufactured), raw component (purchased), BoM, routing.
        UUID fgProductId = UUID.randomUUID();
        UUID rawProductId = UUID.randomUUID();

        mfg.replenishment.put(fgProductId, /*purchased=*/false, /*manufactured=*/true);
        mfg.replenishment.put(rawProductId, /*purchased=*/true, /*manufactured=*/false);
        mfg.bomLookup.put(fgProductId, UUID.randomUUID(),
            InMemoryBomLookup.rawLine(rawProductId, "RM-001", "Raw Material 1",
                new BigDecimal("2"), BigDecimal.ZERO));
        mfg.routings.putSingleOp(fgProductId);

        // Initially: zero stock for the raw material — reservation will fail.
        // (No seedStock for rawProductId.)

        // Step 1: inject ManufacturingRequested onto manufacturing's outbox so
        // the bus delivers it to ManufacturingRequestedHandler.
        // (The bus drains every registered outbox; manufacturing's outbox is
        //  registered and ManufacturingRequestedHandler subscribes to
        //  sales.ManufacturingRequested via its handles() check.)
        UUID salesOrderHeaderId = UUID.randomUUID();
        UUID salesOrderLineId = UUID.randomUUID();
        ManufacturingRequested payload = new ManufacturingRequested(
            UUID.randomUUID(),
            salesOrderHeaderId,
            salesOrderHeaderId,
            List.of(new RequestedLine(
                salesOrderLineId, 10, fgProductId, "FG-001", "Finished Good 1",
                new BigDecimal("5")
            )),
            Instant.now()
        );
        mfg.outbox.appendPending(OutboxRow.pending(
            payload.eventId(), "SalesOrder", salesOrderHeaderId,
            ManufacturingRequested.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null
        ));

        // Step 2: drain. ManufacturingRequestedHandler creates a saga at
        // started and emits ManufacturingDispatched. Inventory's outbox stays
        // empty for now — no reservation attempt yet.
        bus.drain();

        MakeToOrderSaga sagaAtStart = mfg.sagas.all().stream()
            .filter(s -> salesOrderLineId.equals(s.salesOrderLineId()))
            .findFirst()
            .orElseThrow();
        assertThat(sagaAtStart.state()).isEqualTo(MakeToOrderSaga.STARTED);

        // Step 3: drive the worker. started → work_order_created (releases WO).
        mfg.advanceSagaWorker();
        MakeToOrderSaga sagaAfterRelease = mfg.sagas.findBySagaId(sagaAtStart.sagaId()).orElseThrow();
        assertThat(sagaAfterRelease.state()).isEqualTo(MakeToOrderSaga.WORK_ORDER_CREATED);
        assertThat(sagaAfterRelease.workOrderId()).isNotNull();

        // Step 4: drive worker again. work_order_created → raw_material_reservation_requested.
        mfg.advanceSagaWorker();
        MakeToOrderSaga sagaAfterRequest = mfg.sagas.findBySagaId(sagaAtStart.sagaId()).orElseThrow();
        assertThat(sagaAfterRequest.state()).isEqualTo(MakeToOrderSaga.RAW_MATERIAL_RESERVATION_REQUESTED);

        // Step 5: drain. Inventory tries to reserve raw material (none on hand)
        // → emits RawMaterialsReserved with status=failed and a shortage map.
        // Manufacturing's RawMaterialsReservedHandler advances saga to
        // raw_material_shortage and emits RawMaterialShortageDetected.
        bus.drain();
        MakeToOrderSaga sagaAtShortage = mfg.sagas.findBySagaId(sagaAtStart.sagaId()).orElseThrow();
        assertThat(sagaAtShortage.state()).isEqualTo(MakeToOrderSaga.RAW_MATERIAL_SHORTAGE);
        assertThat(mfg.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(RawMaterialShortageDetected.EVENT_TYPE);

        // Step 6: now goods arrive. Seed the raw material stock and inject
        // an inventory.GoodsReceived event for that product.
        inv.seedStock(rawProductId, new BigDecimal("100"));
        UUID receiptEventId = UUID.randomUUID();
        UUID receiptHeaderId = UUID.randomUUID();
        com.northwood.inventory.domain.events.GoodsReceived receiptPayload =
            new com.northwood.inventory.domain.events.GoodsReceived(
                receiptEventId,
                receiptHeaderId,
                "GR-001",
                UUID.randomUUID(),
                InventoryTestKit.DEFAULT_WAREHOUSE_ID,
                "MAIN",
                List.of(new com.northwood.inventory.domain.events.GoodsReceived.ReceivedLine(
                    UUID.randomUUID(), UUID.randomUUID(),
                    rawProductId, "RM-001", "Raw Material 1",
                    new BigDecimal("100"), BigDecimal.ZERO
                )),
                Instant.now()
            );
        inv.outbox.appendPending(OutboxRow.pending(
            receiptEventId, InventoryAggregateTypes.GOODS_RECEIPT, receiptHeaderId,
            GoodsReceived.EVENT_TYPE, 1,
            json.writeValueAsString(receiptPayload),
            null, null, null, null
        ));

        // Step 7: drain. Manufacturing's GoodsReceivedHandler asks the
        // recovery query port for shortage-parked sagas, finds ours, calls
        // unparkOrNarrowShortage which transitions back to work_order_created.
        bus.drain();
        MakeToOrderSaga sagaAfterUnpark = mfg.sagas.findBySagaId(sagaAtStart.sagaId()).orElseThrow();
        assertThat(sagaAfterUnpark.state()).isEqualTo(MakeToOrderSaga.WORK_ORDER_CREATED);

        // Step 8: drive worker. Re-emits RawMaterialReservationRequested.
        mfg.advanceSagaWorker();
        MakeToOrderSaga sagaAtSecondRequest = mfg.sagas.findBySagaId(sagaAtStart.sagaId()).orElseThrow();
        assertThat(sagaAtSecondRequest.state()).isEqualTo(MakeToOrderSaga.RAW_MATERIAL_RESERVATION_REQUESTED);

        // Step 9: drain. Inventory reserves successfully this time (stock
        // available); RawMaterialsReserved with status=reserved → saga to
        // raw_materials_reserved.
        bus.drain();
        MakeToOrderSaga sagaAtSuccess = mfg.sagas.findBySagaId(sagaAtStart.sagaId()).orElseThrow();
        assertThat(sagaAtSuccess.state()).isEqualTo(MakeToOrderSaga.RAW_MATERIALS_RESERVED);
    }
}
