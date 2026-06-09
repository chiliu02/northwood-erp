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
        @Test void full_reservation_shortcuts_to_ready_to_ship() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyStockReserved(SO, "reserved", Set.of());

            assertThat(state).isEqualTo(READY_TO_SHIP);
            assertThat(saga.state()).isEqualTo(READY_TO_SHIP);
        }

        @Test void partial_reservation_parks_with_outstanding_lines() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            manager.applyStockReserved(SO, "partially_reserved", Set.of(LINE_1));

            assertThat(saga.state()).isEqualTo(STOCK_RESERVATION_INCOMPLETE);
            FulfilmentSagaData data = json.readValue(saga.dataJson(), FulfilmentSagaData.class);
            assertThat(data.outstandingReplenishmentLineIds()).containsExactly(LINE_1);
        }

        @Test void failed_reservation_parks_with_outstanding_lines() {
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_REQUESTED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            manager.applyStockReserved(SO, "failed", Set.of(LINE_1, LINE_2));

            assertThat(saga.state()).isEqualTo(STOCK_RESERVATION_INCOMPLETE);
            FulfilmentSagaData data = json.readValue(saga.dataJson(), FulfilmentSagaData.class);
            assertThat(data.outstandingReplenishmentLineIds()).containsExactlyInAnyOrder(LINE_1, LINE_2);
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

        @Test void all_pegged_lines_go_straight_to_ready_to_ship() {
            // order-pegged completions are reserved on completion, so the
            // saga ships without a re-reservation retry.
            SalesOrderFulfilmentSaga saga = sagaInState(STOCK_RESERVATION_INCOMPLETE,
                FulfilmentSagaData.none().withOutstandingReplenishmentLineIds(Set.of(LINE_1)));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyReplenishmentFulfilled(SO, LINE_1, true);   // pegged

            assertThat(state).isEqualTo(READY_TO_SHIP);
            assertThat(saga.state()).isEqualTo(READY_TO_SHIP);
        }

        @Test void mixed_pegged_and_shortage_retries_reservation() {
            // One pegged + one shortage line: the shortage top-up latches
            // sawNonPeggedReplenishment, so the cleared set retries (not ships).
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
            FulfilmentSagaData data = json.readValue(saga.dataJson(), FulfilmentSagaData.class);
            assertThat(data.outstandingReplenishmentLineIds()).containsExactly(LINE_2);
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
            SalesOrderFulfilmentSaga saga = sagaInState(READY_TO_SHIP);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyReplenishmentFulfilled(SO, LINE_1, false);

            assertThat(state).isEqualTo(READY_TO_SHIP);
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
            SalesOrderFulfilmentSaga saga = sagaInState(READY_TO_SHIP);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyReplenishmentCancelled(SO, LINE_1, "no vendor");

            assertThat(state).isEqualTo(READY_TO_SHIP);
            verify(sagas, never()).update(any());
        }
    }

    @Nested
    class ApplyShipmentPosted {
        @Test void ready_to_ship_full_shipment_advances_to_goods_shipped() {
            SalesOrderFulfilmentSaga saga = sagaInState(READY_TO_SHIP);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyShipmentPosted(SO, true);

            assertThat(state).isEqualTo(GOODS_SHIPPED);
        }

        @Test void ready_to_ship_partial_shipment_parks_at_partially_shipped() {
            SalesOrderFulfilmentSaga saga = sagaInState(READY_TO_SHIP);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyShipmentPosted(SO, false);

            assertThat(state).isEqualTo(PARTIALLY_SHIPPED);
        }

        @Test void further_partial_shipment_stays_at_partially_shipped() {
            SalesOrderFulfilmentSaga saga = sagaInState(PARTIALLY_SHIPPED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyShipmentPosted(SO, false);

            assertThat(state).isEqualTo(PARTIALLY_SHIPPED);
        }

        @Test void completing_shipment_from_partially_shipped_advances_to_goods_shipped() {
            SalesOrderFulfilmentSaga saga = sagaInState(PARTIALLY_SHIPPED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyShipmentPosted(SO, true);

            assertThat(state).isEqualTo(GOODS_SHIPPED);
        }

        @Test void unrelated_state_returns_unchanged() {
            SalesOrderFulfilmentSaga saga = sagaInState(INVOICE_CREATED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyShipmentPosted(SO, true);

            assertThat(state).isEqualTo(INVOICE_CREATED);
            verify(sagas, never()).update(any());
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
            verify(sagas, never()).update(any());
        }
    }

    @Nested
    class ApplyCustomerPaymentReceived {
        @Test void order_fully_settled_completes() {
            SalesOrderFulfilmentSaga saga = sagaInState(INVOICE_CREATED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, true, true);

            assertThat(state).isEqualTo(COMPLETED);
        }

        @Test void partial_settlement_transitions_to_invoice_partially_paid() {
            SalesOrderFulfilmentSaga saga = sagaInState(INVOICE_CREATED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, false, false);

            assertThat(state).isEqualTo(INVOICE_PARTIALLY_PAID);
        }

        @Test void one_invoice_paid_but_order_not_settled_does_not_complete() {
            // Partial-shipment case: a per-shipment invoice is fully paid
            // (invoiceFullySettled=true) but another shipment's invoice is still
            // outstanding (orderFullySettled=false) → must NOT complete.
            SalesOrderFulfilmentSaga saga = sagaInState(INVOICE_CREATED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, true, false);

            assertThat(state).isEqualTo(INVOICE_PARTIALLY_PAID);
        }

        @Test void from_invoice_partially_paid_order_settled_completes() {
            SalesOrderFulfilmentSaga saga = sagaInState(INVOICE_PARTIALLY_PAID);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, true, true);

            assertThat(state).isEqualTo(COMPLETED);
        }

        @Test void payment_while_partially_shipped_is_noop() {
            SalesOrderFulfilmentSaga saga = sagaInState(PARTIALLY_SHIPPED);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, true, false);

            assertThat(state).isEqualTo(PARTIALLY_SHIPPED);
            verify(sagas, never()).update(any());
        }

        @Test void unrelated_state_returns_unchanged() {
            SalesOrderFulfilmentSaga saga = sagaInState(READY_TO_SHIP);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, true, true);

            assertThat(state).isEqualTo(READY_TO_SHIP);
            verify(sagas, never()).update(any());
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
            SalesOrderFulfilmentSaga saga = sagaInState(READY_TO_SHIP);
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyInventoryCancellationApplied(SO);

            assertThat(state).isEqualTo(READY_TO_SHIP);
        }

        @Test void no_saga_throws_illegal_state() {
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> manager.applyInventoryCancellationApplied(SO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inventory.SalesOrderCancellationApplied");
            verify(sagas, never()).update(any());
        }
    }

    @Nested
    class ApplyCustomerInvoiceCreatedPrepaymentBranch {
        @Test void awaiting_prepayment_invoice_advances_to_invoice_created() {
            SalesOrderFulfilmentSaga saga = sagaInState(AWAITING_PREPAYMENT_INVOICE,
                FulfilmentSagaData.none().withPaymentTerms("prepayment"));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerInvoiceCreated(SO);

            assertThat(state).isEqualTo(INVOICE_CREATED);
        }
    }

    @Nested
    class ApplyDepositBranch {
        @Test void awaiting_deposit_invoice_advances_to_deposit_invoiced() {
            SalesOrderFulfilmentSaga saga = sagaInState(AWAITING_DEPOSIT_INVOICE,
                FulfilmentSagaData.none().withPaymentTerms("deposit"));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerInvoiceCreated(SO);

            assertThat(state).isEqualTo(DEPOSIT_INVOICED);
        }

        @Test void full_settlement_of_deposit_invoice_advances_to_deposit_paid() {
            SalesOrderFulfilmentSaga saga = sagaInState(DEPOSIT_INVOICED,
                FulfilmentSagaData.none().withPaymentTerms("deposit"));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, true, true);

            assertThat(state).isEqualTo(DEPOSIT_PAID);
        }

        @Test void partial_deposit_payment_stays_at_deposit_invoiced() {
            SalesOrderFulfilmentSaga saga = sagaInState(DEPOSIT_INVOICED,
                FulfilmentSagaData.none().withPaymentTerms("deposit"));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, false, false);

            assertThat(state).isEqualTo(DEPOSIT_INVOICED);
        }
    }

    @Nested
    class ApplyShipmentPostedPrepaymentBranch {
        @Test void prepayment_ready_to_ship_advances_through_goods_shipped_to_completed() {
            SalesOrderFulfilmentSaga saga = sagaInState(READY_TO_SHIP,
                FulfilmentSagaData.none().withPaymentTerms("prepayment"));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyShipmentPosted(SO, true);

            assertThat(state).isEqualTo(COMPLETED);
        }

        @Test void on_shipment_ready_to_ship_stops_at_goods_shipped() {
            SalesOrderFulfilmentSaga saga = sagaInState(READY_TO_SHIP,
                FulfilmentSagaData.none().withPaymentTerms("on_shipment"));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyShipmentPosted(SO, true);

            assertThat(state).isEqualTo(GOODS_SHIPPED);
        }

        @Test void legacy_no_payment_terms_stops_at_goods_shipped() {
            SalesOrderFulfilmentSaga saga = sagaInState(READY_TO_SHIP, FulfilmentSagaData.none());
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyShipmentPosted(SO, true);

            assertThat(state).isEqualTo(GOODS_SHIPPED);
        }
    }

    @Nested
    class ApplyCustomerPaymentReceivedPrepaymentBranch {
        @Test void full_settlement_of_prepayment_invoice_advances_to_prepaid() {
            SalesOrderFulfilmentSaga saga = sagaInState(INVOICE_CREATED,
                FulfilmentSagaData.none().withPaymentTerms("prepayment"));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, true, true);

            assertThat(state).isEqualTo(PREPAID);
        }

        @Test void full_settlement_on_shipment_still_completes() {
            SalesOrderFulfilmentSaga saga = sagaInState(INVOICE_CREATED,
                FulfilmentSagaData.none().withPaymentTerms("on_shipment"));
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, true, true);

            assertThat(state).isEqualTo(COMPLETED);
        }

        @Test void legacy_saga_without_payment_terms_still_completes() {
            SalesOrderFulfilmentSaga saga = sagaInState(INVOICE_CREATED, FulfilmentSagaData.none());
            when(sagas.findBySalesOrderId(SO)).thenReturn(Optional.of(saga));

            String state = manager.applyCustomerPaymentReceived(SO, true, true);

            assertThat(state).isEqualTo(COMPLETED);
        }
    }
}
