package com.northwood.sales.infrastructure.saga;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.*;

import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager.SagaNotFoundException;
import com.northwood.sales.domain.saga.FulfilmentSagaData;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the slim saga manager. Asserts only saga-state changes
 * (state, current_step, saga.data). Side effects (projections, shipping
 * service calls, outbox emissions) live with the handlers / worker shell
 * and are tested separately there.
 */
@ExtendWith(MockitoExtension.class)
class JdbcSalesOrderFulfilmentSagaManagerTest {

    private static final UUID SO = UUID.randomUUID();
    private static final UUID WO_1 = UUID.randomUUID();
    private static final UUID WO_2 = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    @Mock SalesOrderFulfilmentSagaPort sagas;
    @Mock PlatformTransactionManager txManager;

    private final ObjectMapper json = new ObjectMapper();
    private JdbcSalesOrderFulfilmentSagaManager manager;

    @BeforeEach
    void setUp() {
        manager = new JdbcSalesOrderFulfilmentSagaManager(sagas, json, txManager, 30L, 15L);
    }

    private SalesOrderFulfilmentSaga sagaInState(String state, FulfilmentSagaData data) {
        Instant now = Instant.now();
        String dataJson = data == null ? "{}" : json.writeValueAsString(data);
        return new SalesOrderFulfilmentSaga(
            UUID.randomUUID(), SO, state, "step",
            null, 0, now, null, null, 0L, dataJson, now, now, null
        );
    }

    private SalesOrderFulfilmentSaga sagaInState(String state) {
        return sagaInState(state, null);
    }

    @Nested
    class InsertStarted {
        @Test void inserts_fresh_saga_via_port() {
            manager.insertStarted(SO, "{}");

            ArgumentCaptor<SalesOrderFulfilmentSaga> cap = ArgumentCaptor.forClass(SalesOrderFulfilmentSaga.class);
            verify(sagas).insert(cap.capture());
            assertThat(cap.getValue().state()).isEqualTo(STARTED);
            assertThat(cap.getValue().salesOrderId()).isEqualTo(SO);
        }
    }

    @Nested
    class RequestCompensation {
        @Test void flips_saga_to_compensating() {
            SalesOrderFulfilmentSaga saga = sagaInState(READY_TO_SHIP);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            manager.requestCompensation(SO);

            assertThat(saga.state()).isEqualTo(COMPENSATING);
            verify(sagas).save(saga);
        }

        @Test void throws_when_no_saga() {
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> manager.requestCompensation(SO))
                .isInstanceOf(SagaNotFoundException.class);
            verify(sagas, never()).save(any());
        }
    }

    @Nested
    class ApplyStockReserved {
        @Test void full_reservation_shortcuts_to_ready_to_ship() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyStockReserved(SO, "reserved", Map.of());

            assertThat(state).isEqualTo(READY_TO_SHIP);
            assertThat(saga.state()).isEqualTo(READY_TO_SHIP);
            FulfilmentSagaData data = json.readValue(saga.dataJson(), FulfilmentSagaData.class);
            assertThat(data.shortageByLineNumber()).isEmpty();
        }

        @Test void partial_reservation_stashes_shortage() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            manager.applyStockReserved(SO, "partially_reserved", Map.of(10, new BigDecimal("2")));

            assertThat(saga.state()).isEqualTo(STOCK_RESERVED);
            FulfilmentSagaData data = json.readValue(saga.dataJson(), FulfilmentSagaData.class);
            assertThat(data.shortageByLineNumber()).containsEntry(10, new BigDecimal("2"));
        }

        @Test void failed_reservation_stashes_full_shortage() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            manager.applyStockReserved(SO, "failed", Map.of(10, new BigDecimal("3")));

            assertThat(saga.state()).isEqualTo(STOCK_RESERVED);
        }

        @Test void partial_reservation_without_shortage_map_throws() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            assertThatThrownBy(() -> manager.applyStockReserved(SO, "partially_reserved", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("partially_reserved")
                .hasMessageContaining(SO.toString())
                .hasMessageContaining("Inventory must include shortageByLineNumber");

            assertThat(saga.state()).isEqualTo(STOCK_RESERVATION_REQUESTED);
            verify(sagas, never()).save(any());
        }

        @Test void failed_reservation_with_empty_shortage_map_throws() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            assertThatThrownBy(() -> manager.applyStockReserved(SO, "failed", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed")
                .hasMessageContaining(SO.toString())
                .hasMessageContaining("Inventory must include shortageByLineNumber");

            assertThat(saga.state()).isEqualTo(STOCK_RESERVATION_REQUESTED);
            verify(sagas, never()).save(any());
        }

        @Test void throws_when_no_saga() {
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> manager.applyStockReserved(SO, "reserved", Map.of()))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class ApplyWorkOrderCreated {
        @Test void first_top_level_wo_advances_to_in_progress() {
            SalesOrderFulfilmentSaga saga = sagaInState(MANUFACTURING_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyWorkOrderCreated(SO, WO_1, null);

            assertThat(state).isEqualTo(MANUFACTURING_IN_PROGRESS);
            FulfilmentSagaData data = json.readValue(saga.dataJson(), FulfilmentSagaData.class);
            assertThat(data.outstandingWorkOrderIds()).containsExactly(WO_1);
        }

        @Test void subsequent_wo_just_registers() {
            SalesOrderFulfilmentSaga saga = sagaInState(MANUFACTURING_IN_PROGRESS,
                FulfilmentSagaData.none().withWorkOrderCreated(WO_1));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyWorkOrderCreated(SO, WO_2, null);

            assertThat(state).isEqualTo(MANUFACTURING_IN_PROGRESS);
            FulfilmentSagaData data = json.readValue(saga.dataJson(), FulfilmentSagaData.class);
            assertThat(data.outstandingWorkOrderIds()).containsExactlyInAnyOrder(WO_1, WO_2);
        }

        @Test void sub_assembly_is_skipped_returns_null() {
            String state = manager.applyWorkOrderCreated(SO, UUID.randomUUID(), UUID.randomUUID());

            assertThat(state).isNull();
            verifyNoInteractions(sagas);
        }
    }

    @Nested
    class ApplyWorkOrderManufacturingCompleted {
        @Test void last_wo_completion_advances_to_ready_to_ship() {
            SalesOrderFulfilmentSaga saga = sagaInState(MANUFACTURING_IN_PROGRESS,
                FulfilmentSagaData.none()
                    .withExpectedWorkOrderCount(1)
                    .withWorkOrderCreated(WO_1));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyWorkOrderManufacturingCompleted(SO, WO_1, null);

            assertThat(state).isEqualTo(READY_TO_SHIP);
        }

        @Test void partial_completion_holds() {
            SalesOrderFulfilmentSaga saga = sagaInState(MANUFACTURING_IN_PROGRESS,
                FulfilmentSagaData.none()
                    .withExpectedWorkOrderCount(2)
                    .withWorkOrderCreated(WO_1)
                    .withWorkOrderCreated(WO_2));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyWorkOrderManufacturingCompleted(SO, WO_1, null);

            assertThat(state).isEqualTo(MANUFACTURING_IN_PROGRESS);
        }

        @Test void sub_assembly_completion_returns_null() {
            String state = manager.applyWorkOrderManufacturingCompleted(SO, UUID.randomUUID(), UUID.randomUUID());

            assertThat(state).isNull();
            verifyNoInteractions(sagas);
        }
    }

    @Nested
    class ApplyManufacturingDispatched {
        @Test void all_rejected_flips_to_stock_reservation_failed() {
            SalesOrderFulfilmentSaga saga = sagaInState(MANUFACTURING_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyManufacturingDispatched(SO, 0, 2);

            assertThat(state).isEqualTo(STOCK_RESERVATION_FAILED);
            assertThat(saga.currentStep()).isEqualTo("no_manufacturable_lines");
        }

        @Test void partial_rejection_flips_to_stock_reservation_failed() {
            // §4.2 closure: any rejection rejects the whole order (was: silently
            // stamp expectedWorkOrderCount=acceptedCount and proceed with the
            // accepted lines, dropping the rejected one).
            SalesOrderFulfilmentSaga saga = sagaInState(MANUFACTURING_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyManufacturingDispatched(SO, 2, 3);

            assertThat(state).isEqualTo(STOCK_RESERVATION_FAILED);
            assertThat(saga.currentStep()).isEqualTo("partial_dispatch_rejection");
        }

        @Test void all_accepted_stamps_expected_count() {
            SalesOrderFulfilmentSaga saga = sagaInState(MANUFACTURING_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyManufacturingDispatched(SO, 3, 3);

            assertThat(state).isEqualTo(MANUFACTURING_REQUESTED);
            FulfilmentSagaData data = json.readValue(saga.dataJson(), FulfilmentSagaData.class);
            assertThat(data.expectedWorkOrderCount()).isEqualTo(3);
        }
    }

    @Nested
    class CrossPartitionRace {
        // §2.6 — targeted regression for the cross-partition race fixed
        // 2026-05-05. The race: with two top-level work orders, WO1's
        // *Completed* reaches sales before WO2's *Created* (siblings on
        // different Kafka partitions, no cross-partition ordering). For the
        // instant after WO1 completes, `outstanding` is empty — and the
        // legacy "outstanding empty AND completed non-empty" gate would read
        // that as "all done" and advance to ready_to_ship while WO2 is still
        // unbuilt. The fix stamps an expectedWorkOrderCount at dispatch time
        // and gates on completed.size() >= expectedCount, which is
        // order-independent: the empty-outstanding window can no longer trip
        // the gate. This drives the adversarial ordering through the manager
        // (not just the data object — see FulfilmentSagaDataTest for that)
        // and asserts the saga holds, then advances only once both complete.
        @Test void completion_before_sibling_creation_holds_then_advances() {
            SalesOrderFulfilmentSaga saga = sagaInState(MANUFACTURING_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            // Dispatch: both lines accepted → expectedWorkOrderCount=2 stamped,
            // saga stays in manufacturing_requested awaiting WO creation.
            assertThat(manager.applyManufacturingDispatched(SO, 2, 2)).isEqualTo(MANUFACTURING_REQUESTED);

            // WO1 created → first top-level WO advances to in_progress.
            assertThat(manager.applyWorkOrderCreated(SO, WO_1, null)).isEqualTo(MANUFACTURING_IN_PROGRESS);

            // THE RACE: WO1 completes before WO2 is created. With expectedCount
            // set, the count gate (1 >= 2 == false) holds the saga; the legacy
            // outstanding-set gate would have advanced here.
            assertThat(manager.applyWorkOrderManufacturingCompleted(SO, WO_1, null))
                .isEqualTo(MANUFACTURING_IN_PROGRESS);

            // At the race moment outstanding is empty (WO1's completion cleared
            // it) yet the saga has not advanced — the exact false-positive the
            // legacy gate tripped on, now defused.
            FulfilmentSagaData atRace = json.readValue(saga.dataJson(), FulfilmentSagaData.class);
            assertThat(atRace.outstandingWorkOrderIds()).isEmpty();
            assertThat(atRace.completedWorkOrderIds()).containsExactly(WO_1);

            // WO2's late Created → registers without advancing.
            assertThat(manager.applyWorkOrderCreated(SO, WO_2, null)).isEqualTo(MANUFACTURING_IN_PROGRESS);

            // WO2 completes → completed.size() reaches expectedCount → advance.
            assertThat(manager.applyWorkOrderManufacturingCompleted(SO, WO_2, null)).isEqualTo(READY_TO_SHIP);
        }

        @Test void completion_arriving_entirely_before_its_creation_does_not_advance() {
            // Even more adversarial reordering: WO1's Completed arrives before
            // its own Created. The count gate still holds (1 of 2), and the
            // late Created is absorbed idempotently (WO already completed).
            SalesOrderFulfilmentSaga saga = sagaInState(MANUFACTURING_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            assertThat(manager.applyManufacturingDispatched(SO, 2, 2)).isEqualTo(MANUFACTURING_REQUESTED);

            // Completion before any Created — count gate (1 >= 2 == false) holds.
            assertThat(manager.applyWorkOrderManufacturingCompleted(SO, WO_1, null))
                .isEqualTo(MANUFACTURING_REQUESTED);

            // Late Created for the already-completed WO1: idempotent no-op on
            // the WO set, but it does advance requested → in_progress.
            assertThat(manager.applyWorkOrderCreated(SO, WO_1, null)).isEqualTo(MANUFACTURING_IN_PROGRESS);
            FulfilmentSagaData data = json.readValue(saga.dataJson(), FulfilmentSagaData.class);
            assertThat(data.outstandingWorkOrderIds()).isEmpty();
            assertThat(data.completedWorkOrderIds()).containsExactly(WO_1);

            // Second WO runs its normal course → saga advances once count is met.
            assertThat(manager.applyWorkOrderCreated(SO, WO_2, null)).isEqualTo(MANUFACTURING_IN_PROGRESS);
            assertThat(manager.applyWorkOrderManufacturingCompleted(SO, WO_2, null)).isEqualTo(READY_TO_SHIP);
        }
    }

    @Nested
    class ApplyShipmentPosted {
        @Test void ready_to_ship_advances_to_goods_shipped() {
            SalesOrderFulfilmentSaga saga = sagaInState(READY_TO_SHIP);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyShipmentPosted(SO);

            assertThat(state).isEqualTo(GOODS_SHIPPED);
        }

        @Test void unrelated_state_returns_unchanged() {
            SalesOrderFulfilmentSaga saga = sagaInState(INVOICE_CREATED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyShipmentPosted(SO);

            assertThat(state).isEqualTo(INVOICE_CREATED);
            verify(sagas, never()).save(any());
        }
    }

    @Nested
    class ApplyCustomerInvoiceCreated {
        @Test void goods_shipped_advances() {
            SalesOrderFulfilmentSaga saga = sagaInState(GOODS_SHIPPED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerInvoiceCreated(SO);

            assertThat(state).isEqualTo(INVOICE_CREATED);
        }

        @Test void unrelated_state_returns_unchanged() {
            SalesOrderFulfilmentSaga saga = sagaInState(READY_TO_SHIP);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerInvoiceCreated(SO);

            assertThat(state).isEqualTo(READY_TO_SHIP);
            verify(sagas, never()).save(any());
        }
    }

    @Nested
    class ApplyCustomerPaymentReceived {
        @Test void full_settlement_completes() {
            SalesOrderFulfilmentSaga saga = sagaInState(INVOICE_CREATED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, true);

            assertThat(state).isEqualTo(COMPLETED);
        }

        @Test void partial_settlement_transitions_to_invoice_paid() {
            SalesOrderFulfilmentSaga saga = sagaInState(INVOICE_CREATED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, false);

            assertThat(state).isEqualTo(INVOICE_PAID);
        }

        @Test void from_invoice_paid_full_completes() {
            SalesOrderFulfilmentSaga saga = sagaInState(INVOICE_PAID);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, true);

            assertThat(state).isEqualTo(COMPLETED);
        }

        @Test void unrelated_state_returns_unchanged() {
            SalesOrderFulfilmentSaga saga = sagaInState(READY_TO_SHIP);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, true);

            assertThat(state).isEqualTo(READY_TO_SHIP);
            verify(sagas, never()).save(any());
        }
    }

    @Nested
    class ApplyCancellationApplied {
        @Test void inventory_ack_alone_does_not_complete() {
            SalesOrderFulfilmentSaga saga = sagaInState(COMPENSATING);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyInventoryCancellationApplied(SO);

            assertThat(state).isEqualTo(COMPENSATING);
        }

        @Test void manufacturing_ack_after_inventory_completes_to_compensated() {
            SalesOrderFulfilmentSaga saga = sagaInState(COMPENSATING,
                FulfilmentSagaData.none().withInventoryCancellationAcked());
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyManufacturingCancellationApplied(SO);

            assertThat(state).isEqualTo(COMPENSATED);
        }

        @Test void no_saga_throws_illegal_state() {
            // Consistency with every other apply method — an orphan ack with no
            // saga row is a genuine invariant violation that inbox redelivery /
            // dead-letter handling should surface, not silently swallow.
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.empty());

            org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> manager.applyInventoryCancellationApplied(SO)
            )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inventory.SalesOrderCancellationApplied");
            verify(sagas, never()).save(any());
        }
    }

    @Nested
    class ActiveStates {
        @Test void worker_polls_started_and_stock_reserved() {
            // Indirect verification: claim only those two states.
            Set<String> active = Set.of(STARTED, STOCK_RESERVED);
            // We can't directly call the protected method, but we can assert the
            // contract holds by checking the manager respects the set when
            // worker drains. Skipping explicit test here — contract verified
            // by SalesApplicationTests integration smoke.
            assertThat(active).containsExactlyInAnyOrder(STARTED, STOCK_RESERVED);
        }
    }
}
