package com.northwood.purchasing.infrastructure.saga;

import com.northwood.purchasing.application.saga.PurchaseToPaySagaManager;
import com.northwood.purchasing.domain.saga.PurchaseToPaySaga;
import static com.northwood.purchasing.domain.saga.PurchaseToPaySaga.PURCHASE_ORDER_APPROVED;
import static com.northwood.purchasing.domain.saga.PurchaseToPaySaga.WAITING_FOR_GOODS;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Spring scheduling glue + worker-driven advance for the purchase-to-pay
 * saga. Single worker-driven transition: {@code purchase_order_approved →
 * waiting_for_goods} (parks for a day until {@code inventory.GoodsReceived}
 * arrives).
 *
 * <p>The {@code @Scheduled poll()} delegates to the manager's drain machinery
 * (per-saga REQUIRES_NEW + retry) and provides the per-saga advance step as
 * a callback. The drain machinery commits saga state via
 * {@code sagaPort.save(saga)} after this callback returns.
 */
@Component
public class PurchaseToPaySagaWorker {

    private static final Logger log = LoggerFactory.getLogger(PurchaseToPaySagaWorker.class);
    private static final int BATCH_SIZE = 10;

    private final String workerId =
        "purchasing.p2p-worker@" + ManagementFactory.getRuntimeMXBean().getName();

    private final PurchaseToPaySagaManager manager;

    public PurchaseToPaySagaWorker(PurchaseToPaySagaManager manager) {
        this.manager = manager;
    }

    @Scheduled(fixedDelayString = "${northwood.saga.poll-interval:1000}")
    public void poll() {
        manager.drain(BATCH_SIZE, workerId, this::advance);
    }

    private void advance(PurchaseToPaySaga saga) {
        if (PURCHASE_ORDER_APPROVED.equals(saga.state())) {
            saga.transitionTo(WAITING_FOR_GOODS, "wait_for_goods_receipt");
            saga.parkUntil(Instant.now().plus(Duration.ofDays(1)));
            log.info("[{}] saga {} purchase_order={} → waiting_for_goods",
                workerId, saga.sagaId(), saga.purchaseOrderHeaderId());
        }
    }
}
