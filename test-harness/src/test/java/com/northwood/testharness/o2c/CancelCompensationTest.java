package com.northwood.testharness.o2c;

import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.sales.domain.events.SalesOrderCompensated;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.kits.InventoryTestKit;
import com.northwood.testharness.kits.SalesTestKit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.5.1 Phase D — O2C cancel-compensation E2E test.
 *
 * <p>Subsumes §2.6 cancel-order smoke test: walks the saga from
 * {@code placeOrder → cancel → compensating → (inventory ack) → (manufacturing ack)
 * → compensated} entirely through the harness, asserting that
 * {@code sales.SalesOrderCompensated} fires only after BOTH acks land.
 *
 * <p>Manufacturing's ack is injected manually since the
 * {@code ManufacturingTestKit} is a future slice — the gate logic under
 * test is the dual-ack collection in {@code SalesOrderFulfilmentSagaManager},
 * not manufacturing's cancellation flow.
 */
class CancelCompensationTest {

    @Test
    void cancel_records_both_acks_before_emitting_compensated() throws Exception {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        SalesTestKit sales = new SalesTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);

        sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
        UUID productId = UUID.randomUUID();
        sales.pricing.put(productId, new BigDecimal("100.00"), "AUD");
        inventory.seedStock(productId, new BigDecimal("50"));

        // Step 1: place + cancel.
        UUID orderId = sales.placeOrder(new PlaceOrderCommand(
            "SO-CXL-001", "CUST-001",
            LocalDate.of(2026, 5, 15), "AUD",
            List.of(new OrderLine(productId, "WIDGET-001", "Widget",
                new BigDecimal("3"), null, BigDecimal.ZERO))
        ));

        sales.cancel(orderId, "customer changed mind");

        // Saga should be in compensating; sales outbox holds SalesOrderCancellationRequested.
        SalesOrderFulfilmentSaga saga = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(saga.state()).isEqualTo(SalesOrderFulfilmentSaga.COMPENSATING);

        // Step 2: drain the bus. Inventory's cancel handler picks up
        // sales.SalesOrderCancellationRequested → releases (no-op, no
        // reservation existed) → emits inventory.SalesOrderCancellationApplied.
        // Sales' InventoryCancellationAppliedHandler picks it up → records
        // inventory ack on the saga → saga still in compensating (manufacturing
        // ack outstanding).
        bus.drain();

        SalesOrderFulfilmentSaga sagaAfterInvAck = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(sagaAfterInvAck.state())
            .as("inventory ack alone does not complete compensation")
            .isEqualTo(SalesOrderFulfilmentSaga.COMPENSATING);

        // Step 3: manually inject manufacturing.SalesOrderCancellationApplied
        // (ManufacturingTestKit is a later slice). The shape mirrors
        // inventory's CancellationAppliedPayload.
        UUID mfgAckId = UUID.randomUUID();
        Map<String, Object> mfgAckPayload = Map.of(
            "eventId", mfgAckId,
            "aggregateId", orderId,
            "workOrdersCancelled", 0,
            "occurredAt", Instant.now()
        );
        sales.outbox.appendPending(OutboxRow.pending(
            mfgAckId,
            "WorkOrder",
            orderId,
            com.northwood.manufacturing.domain.events.SalesOrderCancellationApplied.EVENT_TYPE,
            1,
            json.writeValueAsString(mfgAckPayload),
            null, null, null, null
        ));

        bus.drain();

        // Step 4: assertions.
        SalesOrderFulfilmentSaga sagaFinal = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(sagaFinal.state())
            .as("both acks → compensated")
            .isEqualTo(SalesOrderFulfilmentSaga.COMPENSATED);

        assertThat(sales.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(SalesOrderCancellationRequested.EVENT_TYPE, SalesOrderCompensated.EVENT_TYPE);

        assertThat(inventory.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(com.northwood.inventory.domain.events.SalesOrderCancellationApplied.EVENT_TYPE);

        assertThat(sales.outbox.findPending(100)).isEmpty();
        assertThat(inventory.outbox.findPending(100)).isEmpty();
    }
}
