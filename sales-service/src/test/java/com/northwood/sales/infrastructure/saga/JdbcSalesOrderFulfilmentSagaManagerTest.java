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

import java.time.Instant;
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
 *
 * <p>Post-prune model: the saga's forward work ends at {@code supply_secured};
 * the ship → invoice → pay leg is a <b>completion gate</b> over two saga.data
 * flags ({@code orderShipped} + {@code orderSettled}). Prepayment/deposit gate at
 * {@code awaiting_prepayment} until the up-front payment, then go through the
 * unified {@code prepaid} active checkpoint.
 */
@ExtendWith(MockitoExtension.class)
class JdbcSalesOrderFulfilmentSagaManagerTest {

    private static final UUID SO = UUID.randomUUID();
    private static final UUID LINE_1 = UUID.randomUUID();
    private static final UUID LINE_2 = UUID.randomUUID();

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

    private FulfilmentSagaData data(SalesOrderFulfilmentSaga saga) {
        return json.readValue(saga.dataJson(), FulfilmentSagaData.class);
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
            // supply_secured is NON-terminal: a cancel before shipment still
            // compensates (the top-down broadcast cancel path).
            SalesOrderFulfilmentSaga saga = sagaInState(SUPPLY_SECURED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            manager.requestCompensation(SO);

            assertThat(saga.state()).isEqualTo(COMPENSATING);
            verify(sagas).update(saga);
        }

        @Test void throws_when_no_saga() {
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> manager.requestCompensation(SO))
                .isInstanceOf(SagaNotFoundException.class);
            verify(sagas, never()).update(any());
        }
    }

    @Nested
    class ApplyStockReserved {
        @Test void full_reservation_shortcuts_to_supply_secured() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyStockReserved(SO, "reserved", Set.of());

            assertThat(state).isEqualTo(SUPPLY_SECURED);
            assertThat(saga.state()).isEqualTo(SUPPLY_SECURED);
        }

        @Test void partial_reservation_parks_with_outstanding_lines() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            manager.applyStockReserved(SO, "partially_reserved", Set.of(LINE_1));

            assertThat(saga.state()).isEqualTo(STOCK_RESERVATION_INCOMPLETE);
            assertThat(data(saga).outstandingReplenishmentLineIds()).containsExactly(LINE_1);
        }

        @Test void failed_reservation_parks_with_outstanding_lines() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            manager.applyStockReserved(SO, "failed", Set.of(LINE_1, LINE_2));

            assertThat(saga.state()).isEqualTo(STOCK_RESERVATION_INCOMPLETE);
            assertThat(data(saga).outstandingReplenishmentLineIds()).containsExactlyInAnyOrder(LINE_1, LINE_2);
        }

        @Test void partial_reservation_without_short_lines_throws() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            assertThatThrownBy(() -> manager.applyStockReserved(SO, "partially_reserved", Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("partially_reserved")
                .hasMessageContaining(SO.toString())
                .hasMessageContaining("Inventory must report the per-line shortage");

            assertThat(saga.state()).isEqualTo(STOCK_RESERVATION_REQUESTED);
            verify(sagas, never()).update(any());
        }

        @Test void throws_when_no_saga() {
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> manager.applyStockReserved(SO, "reserved", Set.of()))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void reserved_with_outstanding_amended_line_stays_incomplete() {
            // Ordering guard: a SalesOrderLineReservationChanged (short) landed
            // first and registered an outstanding amended line; a now-arriving
            // 'reserved' for the original lines must not clobber it to supply_secured.
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_REQUESTED,
                FulfilmentSagaData.none().withOutstandingReplenishmentLineIds(Set.of(LINE_1)));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyStockReserved(SO, "reserved", Set.of());

            assertThat(state).isEqualTo(STOCK_RESERVATION_INCOMPLETE);
            assertThat(saga.state()).isEqualTo(STOCK_RESERVATION_INCOMPLETE);
        }

        @Test void late_reply_after_compensation_is_ignored() {
            // A StockReserved in flight when the order was cancelled lands after
            // the saga reached compensating. Source-state guard: it must NOT
            // resurrect the saga to supply_secured — that would both revive a
            // cancelled order and strand its compensation. The reply is a no-op.
            SalesOrderFulfilmentSaga saga = sagaInState(COMPENSATING);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyStockReserved(SO, "reserved", Set.of());

            assertThat(state).isEqualTo(COMPENSATING);
            assertThat(saga.state()).isEqualTo(COMPENSATING);
            verify(sagas, never()).update(any());
        }
    }

    @Nested
    class ApplyLineReservationChanged {
        @Test void short_amended_line_parks_at_incomplete_and_registers_outstanding() {
            SalesOrderFulfilmentSaga saga = sagaInState(SUPPLY_SECURED, FulfilmentSagaData.none());
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyLineReservationChanged(SO, LINE_1, true);

            assertThat(state).isEqualTo(STOCK_RESERVATION_INCOMPLETE);
            assertThat(data(saga).outstandingReplenishmentLineIds()).containsExactly(LINE_1);
        }

        @Test void reserved_amended_line_clears_outstanding_and_unparks_to_supply_secured() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_INCOMPLETE,
                FulfilmentSagaData.none().withOutstandingReplenishmentLineIds(Set.of(LINE_1)));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyLineReservationChanged(SO, LINE_1, false);

            assertThat(state).isEqualTo(SUPPLY_SECURED);
            assertThat(data(saga).outstandingReplenishmentLineIds()).isEmpty();
        }

        @Test void reserved_amended_line_with_others_outstanding_stays_incomplete() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_INCOMPLETE,
                FulfilmentSagaData.none().withOutstandingReplenishmentLineIds(Set.of(LINE_1, LINE_2)));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyLineReservationChanged(SO, LINE_1, false);

            assertThat(state).isEqualTo(STOCK_RESERVATION_INCOMPLETE);
            assertThat(data(saga).outstandingReplenishmentLineIds()).containsExactly(LINE_2);
        }

        @Test void reserved_amended_line_at_supply_secured_stays_supply_secured() {
            SalesOrderFulfilmentSaga saga = sagaInState(SUPPLY_SECURED, FulfilmentSagaData.none());
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyLineReservationChanged(SO, LINE_1, false);

            assertThat(state).isEqualTo(SUPPLY_SECURED);
        }

        @Test void out_of_phase_is_noop() {
            SalesOrderFulfilmentSaga saga = sagaInState(COMPLETED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyLineReservationChanged(SO, LINE_1, true);

            assertThat(state).isEqualTo(COMPLETED);
            verify(sagas, never()).update(any());
        }
    }

    @Nested
    class ApplyReplenishmentFulfilled {
        @Test void last_outstanding_line_re_enters_stock_reservation_requested() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_INCOMPLETE,
                FulfilmentSagaData.none().withOutstandingReplenishmentLineIds(Set.of(LINE_1)));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyReplenishmentFulfilled(SO, LINE_1, false);   // shortage top-up

            assertThat(state).isEqualTo(STOCK_RESERVATION_REQUESTED);
            assertThat(saga.state()).isEqualTo(STOCK_RESERVATION_REQUESTED);
        }

        @Test void all_pegged_lines_go_straight_to_supply_secured() {
            // order-pegged completions are reserved on completion, so the
            // saga reaches supply readiness without a re-reservation retry.
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_INCOMPLETE,
                FulfilmentSagaData.none().withOutstandingReplenishmentLineIds(Set.of(LINE_1)));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyReplenishmentFulfilled(SO, LINE_1, true);   // pegged

            assertThat(state).isEqualTo(SUPPLY_SECURED);
            assertThat(saga.state()).isEqualTo(SUPPLY_SECURED);
        }

        @Test void mixed_pegged_and_shortage_retries_reservation() {
            // One pegged + one shortage line: the shortage top-up latches
            // sawNonPeggedReplenishment, so the cleared set retries (not secures).
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_INCOMPLETE,
                FulfilmentSagaData.none().withOutstandingReplenishmentLineIds(Set.of(LINE_1, LINE_2)));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            manager.applyReplenishmentFulfilled(SO, LINE_1, true);    // pegged
            String state = manager.applyReplenishmentFulfilled(SO, LINE_2, false);  // shortage

            assertThat(state).isEqualTo(STOCK_RESERVATION_REQUESTED);
        }

        @Test void partial_fulfilment_holds_and_decrements() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_INCOMPLETE,
                FulfilmentSagaData.none().withOutstandingReplenishmentLineIds(Set.of(LINE_1, LINE_2)));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyReplenishmentFulfilled(SO, LINE_1, false);

            assertThat(state).isEqualTo(STOCK_RESERVATION_INCOMPLETE);
            assertThat(data(saga).outstandingReplenishmentLineIds()).containsExactly(LINE_2);
        }

        @Test void duplicate_fulfilment_is_idempotent_noop() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_INCOMPLETE,
                FulfilmentSagaData.none().withOutstandingReplenishmentLineIds(Set.of(LINE_2)));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyReplenishmentFulfilled(SO, LINE_1, false);   // not outstanding

            assertThat(state).isEqualTo(STOCK_RESERVATION_INCOMPLETE);
            verify(sagas, never()).update(any());
        }

        @Test void late_delivery_on_advanced_saga_is_noop() {
            SalesOrderFulfilmentSaga saga = sagaInState(SUPPLY_SECURED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyReplenishmentFulfilled(SO, LINE_1, false);

            assertThat(state).isEqualTo(SUPPLY_SECURED);
            verify(sagas, never()).update(any());
        }
    }

    @Nested
    class ApplyReplenishmentCancelled {
        @Test void cancel_while_awaiting_replenishment_rejects() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_INCOMPLETE,
                FulfilmentSagaData.none().withOutstandingReplenishmentLineIds(Set.of(LINE_1, LINE_2)));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyReplenishmentCancelled(SO, LINE_1, "no active BOM");

            assertThat(state).isEqualTo(REJECTED);
            assertThat(saga.state()).isEqualTo(REJECTED);
            assertThat(saga.currentStep()).isEqualTo("replenishment_cancelled");
        }

        @Test void late_cancel_on_advanced_saga_is_noop() {
            SalesOrderFulfilmentSaga saga = sagaInState(SUPPLY_SECURED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyReplenishmentCancelled(SO, LINE_1, "no vendor");

            assertThat(state).isEqualTo(SUPPLY_SECURED);
            verify(sagas, never()).update(any());
        }
    }

    @Nested
    class ApplyShipmentPostedGate {
        @Test void completing_shipment_latches_shipped_and_holds_for_payment() {
            SalesOrderFulfilmentSaga saga = sagaInState(SUPPLY_SECURED, FulfilmentSagaData.none());
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyShipmentPosted(SO, true);

            assertThat(state).isEqualTo(SUPPLY_SECURED);   // pay leg not yet landed
            assertThat(data(saga).isOrderShipped()).isTrue();
            assertThat(data(saga).isOrderSettled()).isFalse();
            verify(sagas).update(saga);                    // the flag is persisted
        }

        @Test void completing_shipment_completes_when_already_settled() {
            // Prepayment: the order was settled up front, so the completing
            // shipment closes the gate.
            SalesOrderFulfilmentSaga saga = sagaInState(SUPPLY_SECURED,
                FulfilmentSagaData.none().withOrderSettled());
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyShipmentPosted(SO, true);

            assertThat(state).isEqualTo(COMPLETED);
        }

        @Test void partial_shipment_is_noop() {
            // The header partially_shipped status is the line fold's job, not the
            // saga's — a partial shipment doesn't touch the gate.
            SalesOrderFulfilmentSaga saga = sagaInState(SUPPLY_SECURED, FulfilmentSagaData.none());
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyShipmentPosted(SO, false);

            assertThat(state).isEqualTo(SUPPLY_SECURED);
            assertThat(data(saga).isOrderShipped()).isFalse();
            verify(sagas, never()).update(any());
        }

        @Test void shipment_outside_supply_secured_is_noop() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyShipmentPosted(SO, true);

            assertThat(state).isEqualTo(STOCK_RESERVATION_REQUESTED);
            verify(sagas, never()).update(any());
        }
    }

    @Nested
    class ApplyCustomerPaymentReceivedGate {
        @Test void order_settling_payment_latches_settled_and_holds_for_ship() {
            SalesOrderFulfilmentSaga saga = sagaInState(SUPPLY_SECURED, FulfilmentSagaData.none());
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, true, true);

            assertThat(state).isEqualTo(SUPPLY_SECURED);   // ship leg not yet landed
            assertThat(data(saga).isOrderSettled()).isTrue();
            verify(sagas).update(saga);
        }

        @Test void order_settling_payment_completes_when_already_shipped() {
            SalesOrderFulfilmentSaga saga = sagaInState(SUPPLY_SECURED,
                FulfilmentSagaData.none().withOrderShipped());
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, true, true);

            assertThat(state).isEqualTo(COMPLETED);
        }

        @Test void non_order_settling_payment_is_noop() {
            // One of several per-shipment invoices paid in full, but the order
            // still owes another → gate holds, no flag latched.
            SalesOrderFulfilmentSaga saga = sagaInState(SUPPLY_SECURED, FulfilmentSagaData.none());
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, true, false);

            assertThat(state).isEqualTo(SUPPLY_SECURED);
            assertThat(data(saga).isOrderSettled()).isFalse();
            verify(sagas, never()).update(any());
        }

        @Test void payment_outside_the_gate_is_noop() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, true, true);

            assertThat(state).isEqualTo(STOCK_RESERVATION_REQUESTED);
            verify(sagas, never()).update(any());
        }
    }

    @Nested
    class UpfrontPaymentGate {
        @Test void prepayment_full_payment_advances_to_prepaid_and_latches_settled() {
            SalesOrderFulfilmentSaga saga = sagaInState(AWAITING_PREPAYMENT,
                FulfilmentSagaData.none().withPaymentTerms("prepayment"));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            // prepayment: the single invoice = whole order → orderFullySettled=true.
            String state = manager.applyCustomerPaymentReceived(SO, true, true);

            assertThat(state).isEqualTo(PREPAID);
            assertThat(data(saga).isOrderSettled()).isTrue();   // pre-latched for the later gate
        }

        @Test void deposit_payment_advances_to_prepaid_without_latching_settled() {
            SalesOrderFulfilmentSaga saga = sagaInState(AWAITING_PREPAYMENT,
                FulfilmentSagaData.none().withPaymentTerms("deposit"));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            // Realistic deposit case: at deposit-payment time the balance invoice
            // doesn't exist yet, so the single deposit invoice being fully paid
            // makes orderFullySettled SPURIOUSLY true. The gate must NOT latch
            // settled for deposit terms — the balance still has to be paid.
            String state = manager.applyCustomerPaymentReceived(SO, true, true);

            assertThat(state).isEqualTo(PREPAID);
            assertThat(data(saga).isOrderSettled()).isFalse();   // balance lands post-ship
        }

        @Test void partial_upfront_payment_stays_at_awaiting_prepayment() {
            SalesOrderFulfilmentSaga saga = sagaInState(AWAITING_PREPAYMENT,
                FulfilmentSagaData.none().withPaymentTerms("prepayment"));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, false, false);

            assertThat(state).isEqualTo(AWAITING_PREPAYMENT);
            verify(sagas, never()).update(any());
        }
    }

    @Nested
    class CompletionGateOrderIndependence {
        @Test void ship_then_pay_completes() {
            SalesOrderFulfilmentSaga saga = sagaInState(SUPPLY_SECURED, FulfilmentSagaData.none());
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            assertThat(manager.applyShipmentPosted(SO, true)).isEqualTo(SUPPLY_SECURED);
            assertThat(manager.applyCustomerPaymentReceived(SO, true, true)).isEqualTo(COMPLETED);
        }

        @Test void pay_then_ship_completes() {
            SalesOrderFulfilmentSaga saga = sagaInState(SUPPLY_SECURED, FulfilmentSagaData.none());
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            assertThat(manager.applyCustomerPaymentReceived(SO, true, true)).isEqualTo(SUPPLY_SECURED);
            assertThat(manager.applyShipmentPosted(SO, true)).isEqualTo(COMPLETED);
        }
    }

    @Nested
    class ApplyCancellationApplied {
        // Inventory is the sole compensation ack (manufacturing leg retired),
        // so an inventory ack from compensating completes the compensation outright.
        @Test void inventory_ack_from_compensating_completes_to_compensated() {
            SalesOrderFulfilmentSaga saga = sagaInState(COMPENSATING);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyInventoryCancellationApplied(SO);

            assertThat(state).isEqualTo(COMPENSATED);
        }

        @Test void inventory_ack_outside_compensating_does_not_complete() {
            SalesOrderFulfilmentSaga saga = sagaInState(SUPPLY_SECURED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyInventoryCancellationApplied(SO);

            assertThat(state).isEqualTo(SUPPLY_SECURED);
        }

        @Test void no_saga_throws_illegal_state() {
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> manager.applyInventoryCancellationApplied(SO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inventory.SalesOrderCancellationApplied");
            verify(sagas, never()).update(any());
        }
    }
}
