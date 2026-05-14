package com.northwood.purchasing.infrastructure.saga;

import com.northwood.finance.domain.events.SupplierInvoiceApproved;
import com.northwood.finance.domain.events.SupplierPaymentMade;
import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.purchasing.application.saga.PurchaseToPaySagaManager;
import com.northwood.purchasing.domain.saga.PurchaseToPaySaga;
import static com.northwood.purchasing.domain.saga.PurchaseToPaySaga.*;
import com.northwood.purchasing.application.saga.PurchaseToPaySagaPort;
import com.northwood.shared.application.saga.SagaManager;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC-backed purchase-to-pay saga manager. Saga state truth — every
 * transition the saga can take is a method here. Holds <i>only</i>
 * {@link PurchaseToPaySagaPort} (inherited as {@code sagaPort}); no
 * {@code ObjectMapper} needed (P2P saga.data is always empty). All side
 * effects (event emission, projection writes, calls into other services /
 * aggregates) live with the caller — the worker shell for worker-driven
 * advances and the inbox handler shells for inbox-driven advances.
 */
@Service
public class JdbcPurchaseToPaySagaManager
    extends SagaManager<PurchaseToPaySaga, PurchaseToPaySagaPort>
    implements PurchaseToPaySagaManager {

    public JdbcPurchaseToPaySagaManager(
        PurchaseToPaySagaPort sagaPort,
        PlatformTransactionManager transactionManager
    ) {
        super(sagaPort, transactionManager, Duration.ofSeconds(30), Duration.ofSeconds(15));
    }

    @Override
    protected Set<String> activeStates() {
        // 'started' is no longer worker-driven (approve() flips it inline);
        // worker only advances 'purchase_order_approved'.
        return Set.of(PURCHASE_ORDER_APPROVED);
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    @Override
    @Transactional
    public void insertStarted(UUID purchaseOrderHeaderId) {
        sagaPort.insert(PurchaseToPaySaga.started(purchaseOrderHeaderId));
    }

    @Override
    @Transactional
    public String approve(UUID purchaseOrderHeaderId) {
        PurchaseToPaySaga saga = sagaPort.findByPurchaseOrderId(purchaseOrderHeaderId).orElse(null);
        if (saga == null) {
            return null;
        }
        if (STARTED.equals(saga.state())) {
            saga.transitionTo(PURCHASE_ORDER_APPROVED, "wait_for_worker_pickup");
            sagaPort.save(saga);
            log.info("saga {} purchase_order={} → purchase_order_approved",
                saga.sagaId(), purchaseOrderHeaderId);
        }
        return saga.state();
    }

    // ============================================================
    // Inbox-driven transitions
    // ============================================================

    @Override
    @Transactional
    public String applyGoodsReceived(UUID purchaseOrderHeaderId, boolean fullyReceived) {
        PurchaseToPaySaga saga = requireSaga(purchaseOrderHeaderId, GoodsReceived.EVENT_TYPE);

        if (fullyReceived && WAITING_FOR_GOODS.equals(saga.state())) {
            saga.transitionTo(GOODS_RECEIVED, "wait_for_supplier_invoice");
            sagaPort.save(saga);
            log.info("saga {} purchase_order={} → goods_received (fully received)",
                saga.sagaId(), purchaseOrderHeaderId);
        }
        return saga.state();
    }

    @Override
    @Transactional
    public String applySupplierInvoiceApproved(UUID purchaseOrderHeaderId) {
        PurchaseToPaySaga saga = requireSaga(purchaseOrderHeaderId, SupplierInvoiceApproved.EVENT_TYPE);

        if (GOODS_RECEIVED.equals(saga.state())) {
            saga.transitionTo(SUPPLIER_INVOICE_APPROVED, "wait_for_payment");
            sagaPort.save(saga);
            log.info("saga {} purchase_order={} → supplier_invoice_approved",
                saga.sagaId(), purchaseOrderHeaderId);
        } else {
            log.debug("saga {} purchase_order={} not in goods_received (state={}); ignoring",
                saga.sagaId(), purchaseOrderHeaderId, saga.state());
        }
        return saga.state();
    }

    @Override
    @Transactional
    public String applySupplierPaymentMade(UUID purchaseOrderHeaderId, boolean fullySettled) {
        PurchaseToPaySaga saga = requireSaga(purchaseOrderHeaderId, SupplierPaymentMade.EVENT_TYPE);

        if (!SUPPLIER_INVOICE_APPROVED.equals(saga.state())
            && !SUPPLIER_PAYMENT_MADE.equals(saga.state())) {
            log.debug("saga {} purchase_order={} not in payment-receivable state (state={}); ignoring",
                saga.sagaId(), purchaseOrderHeaderId, saga.state());
        } else if (fullySettled) {
            saga.transitionTo(COMPLETED, "p2p_completed");
            sagaPort.save(saga);
            log.info("saga {} purchase_order={} → completed (fully settled)",
                saga.sagaId(), purchaseOrderHeaderId);
        } else {
            saga.transitionTo(SUPPLIER_PAYMENT_MADE, "wait_for_remaining_payments");
            sagaPort.save(saga);
            log.info("saga {} purchase_order={} → supplier_payment_made (partial)",
                saga.sagaId(), purchaseOrderHeaderId);
        }
        return saga.state();
    }

    // ============================================================
    // Internal helpers
    // ============================================================

    private PurchaseToPaySaga requireSaga(UUID purchaseOrderHeaderId, String eventName) {
        return sagaPort.findByPurchaseOrderId(purchaseOrderHeaderId)
            .orElseThrow(() -> new IllegalStateException(
                "No P2P saga for purchase_order_header_id=" + purchaseOrderHeaderId
                    + "; cannot apply " + eventName));
    }
}
