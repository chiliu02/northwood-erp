package com.northwood.purchasing.infrastructure.saga;

import static com.northwood.purchasing.domain.saga.PurchaseToPaySaga.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.purchasing.domain.saga.PurchaseToPaySaga;
import com.northwood.purchasing.application.saga.PurchaseToPaySagaPort;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Unit tests for the slim purchase-to-pay saga manager. Asserts only
 * saga-state changes (state, current_step). Side effects (projection writes,
 * outbox emissions) live with the handlers / worker shell and are tested
 * separately there.
 */
@ExtendWith(MockitoExtension.class)
class JdbcPurchaseToPaySagaManagerTest {

    private static final UUID PO = UUID.randomUUID();

    @Mock PurchaseToPaySagaPort sagas;
    @Mock PlatformTransactionManager txManager;

    private JdbcPurchaseToPaySagaManager manager;

    @BeforeEach
    void setUp() {
        manager = new JdbcPurchaseToPaySagaManager(sagas, txManager, 30L, 15L);
    }

    private PurchaseToPaySaga sagaInState(String state) {
        Instant now = Instant.now();
        return new PurchaseToPaySaga(
            UUID.randomUUID(), PO, null,
            state, "step", null, 0, now, null, null,
            0L, "{}", now, now, null
        );
    }

    @Nested
    class Lifecycle {
        @Test void insertStarted_inserts_at_started_with_sales_order_key() {
            UUID salesOrderId = UUID.randomUUID();
            manager.insertStarted(PO, salesOrderId);

            ArgumentCaptor<PurchaseToPaySaga> cap = ArgumentCaptor.forClass(PurchaseToPaySaga.class);
            verify(sagas).insert(cap.capture());
            assertThat(cap.getValue().state()).isEqualTo(STARTED);
            assertThat(cap.getValue().purchaseOrderHeaderId()).isEqualTo(PO);
            assertThat(cap.getValue().salesOrderHeaderId()).isEqualTo(salesOrderId);   // §1J
        }

        @Test void insertStarted_allows_null_sales_order_key_for_manual_po() {
            manager.insertStarted(PO, null);

            ArgumentCaptor<PurchaseToPaySaga> cap = ArgumentCaptor.forClass(PurchaseToPaySaga.class);
            verify(sagas).insert(cap.capture());
            assertThat(cap.getValue().salesOrderHeaderId()).isNull();
        }

        @Test void approve_flips_started_to_purchase_order_approved() {
            PurchaseToPaySaga saga = sagaInState(STARTED);
            when(sagas.findByPurchaseOrderId(PO)).thenReturn(Optional.of(saga));

            String state = manager.approve(PO);

            assertThat(state).isEqualTo(PURCHASE_ORDER_APPROVED);
            verify(sagas).update(saga);
        }

        @Test void approve_idempotent_on_already_approved() {
            PurchaseToPaySaga saga = sagaInState(PURCHASE_ORDER_APPROVED);
            when(sagas.findByPurchaseOrderId(PO)).thenReturn(Optional.of(saga));

            String state = manager.approve(PO);

            assertThat(state).isEqualTo(PURCHASE_ORDER_APPROVED);
            verify(sagas, never()).update(any());
        }

        @Test void approve_returns_null_when_no_saga() {
            when(sagas.findByPurchaseOrderId(PO)).thenReturn(Optional.empty());

            String state = manager.approve(PO);

            assertThat(state).isNull();
            verify(sagas, never()).update(any());
        }
    }

    @Nested
    class ApplyGoodsReceived {
        @Test void fully_received_advances_to_goods_received() {
            PurchaseToPaySaga saga = sagaInState(WAITING_FOR_GOODS);
            when(sagas.findByPurchaseOrderId(PO)).thenReturn(Optional.of(saga));

            String state = manager.applyGoodsReceived(PO, true);

            assertThat(state).isEqualTo(GOODS_RECEIVED);
            verify(sagas).update(saga);
        }

        @Test void partial_receipt_stays_at_waiting_for_goods() {
            PurchaseToPaySaga saga = sagaInState(WAITING_FOR_GOODS);
            when(sagas.findByPurchaseOrderId(PO)).thenReturn(Optional.of(saga));

            String state = manager.applyGoodsReceived(PO, false);

            assertThat(state).isEqualTo(WAITING_FOR_GOODS);
            verify(sagas, never()).update(any());
        }

        @Test void no_saga_throws() {
            when(sagas.findByPurchaseOrderId(PO)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> manager.applyGoodsReceived(PO, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No P2P saga");
        }
    }

    @Nested
    class ApplySupplierInvoiceApproved {
        @Test void goods_received_advances_to_supplier_invoice_approved() {
            PurchaseToPaySaga saga = sagaInState(GOODS_RECEIVED);
            when(sagas.findByPurchaseOrderId(PO)).thenReturn(Optional.of(saga));

            String state = manager.applySupplierInvoiceApproved(PO);

            assertThat(state).isEqualTo(SUPPLIER_INVOICE_APPROVED);
        }

        @Test void unrelated_state_returns_unchanged() {
            PurchaseToPaySaga saga = sagaInState(WAITING_FOR_GOODS);
            when(sagas.findByPurchaseOrderId(PO)).thenReturn(Optional.of(saga));

            String state = manager.applySupplierInvoiceApproved(PO);

            assertThat(state).isEqualTo(WAITING_FOR_GOODS);
            verify(sagas, never()).update(any());
        }
    }

    @Nested
    class ApplySupplierInvoiceRejected {
        @Test void goods_received_advances_to_failed() {
            PurchaseToPaySaga saga = sagaInState(GOODS_RECEIVED);
            when(sagas.findByPurchaseOrderId(PO)).thenReturn(Optional.of(saga));

            String state = manager.applySupplierInvoiceRejected(PO);

            assertThat(state).isEqualTo(FAILED);
            assertThat(saga.currentStep()).isEqualTo("supplier_invoice_rejected");
            verify(sagas).update(saga);
        }

        @Test void unrelated_state_returns_unchanged() {
            PurchaseToPaySaga saga = sagaInState(SUPPLIER_INVOICE_APPROVED);
            when(sagas.findByPurchaseOrderId(PO)).thenReturn(Optional.of(saga));

            String state = manager.applySupplierInvoiceRejected(PO);

            assertThat(state).isEqualTo(SUPPLIER_INVOICE_APPROVED);
            verify(sagas, never()).update(any());
        }

        @Test void no_saga_throws() {
            when(sagas.findByPurchaseOrderId(PO)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> manager.applySupplierInvoiceRejected(PO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No P2P saga");
        }
    }

    @Nested
    class ApplySupplierPaymentMade {
        @Test void full_settlement_completes() {
            PurchaseToPaySaga saga = sagaInState(SUPPLIER_INVOICE_APPROVED);
            when(sagas.findByPurchaseOrderId(PO)).thenReturn(Optional.of(saga));

            String state = manager.applySupplierPaymentMade(PO, true);

            assertThat(state).isEqualTo(COMPLETED);
        }

        @Test void partial_settlement_transitions_to_supplier_partially_paid() {
            PurchaseToPaySaga saga = sagaInState(SUPPLIER_INVOICE_APPROVED);
            when(sagas.findByPurchaseOrderId(PO)).thenReturn(Optional.of(saga));

            String state = manager.applySupplierPaymentMade(PO, false);

            assertThat(state).isEqualTo(SUPPLIER_PARTIALLY_PAID);
        }

        @Test void from_supplier_partially_paid_full_completes() {
            PurchaseToPaySaga saga = sagaInState(SUPPLIER_PARTIALLY_PAID);
            when(sagas.findByPurchaseOrderId(PO)).thenReturn(Optional.of(saga));

            String state = manager.applySupplierPaymentMade(PO, true);

            assertThat(state).isEqualTo(COMPLETED);
        }

        @Test void unrelated_state_returns_unchanged() {
            PurchaseToPaySaga saga = sagaInState(WAITING_FOR_GOODS);
            when(sagas.findByPurchaseOrderId(PO)).thenReturn(Optional.of(saga));

            String state = manager.applySupplierPaymentMade(PO, true);

            assertThat(state).isEqualTo(WAITING_FOR_GOODS);
            verify(sagas, never()).update(any());
        }
    }
}
