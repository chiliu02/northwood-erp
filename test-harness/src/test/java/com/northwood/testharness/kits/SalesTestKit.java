package com.northwood.testharness.kits;

import com.northwood.sales.application.dto.CancelOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.SalesOrderCompensationEmitter;
import com.northwood.sales.application.SalesOrderService;
import com.northwood.sales.application.inbox.CustomerInvoiceCreatedHandler;
import com.northwood.sales.application.inbox.CustomerPaymentReceivedHandler;
import com.northwood.sales.application.inbox.InventoryCancellationAppliedHandler;
import com.northwood.sales.application.inbox.ManufacturingCancellationAppliedHandler;
import com.northwood.sales.application.inbox.ManufacturingDispatchedHandler;
import com.northwood.sales.application.inbox.ShipmentPostedHandler;
import com.northwood.sales.application.inbox.StockReservedHandler;
import com.northwood.sales.application.inbox.WorkOrderCreatedHandler;
import com.northwood.sales.application.inbox.WorkOrderManufacturingCompletedHandler;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.sales.infrastructure.saga.JdbcSalesOrderFulfilmentSagaManager;
import com.northwood.sales.infrastructure.saga.SalesOrderFulfilmentSagaWorker;
import com.northwood.shared.application.outbox.OutboxAppender;
import com.northwood.shared.application.security.CurrentUserAccessor;
import com.northwood.testharness.inmemory.InMemoryInboxPort;
import com.northwood.testharness.inmemory.InMemoryOutboxPort;
import com.northwood.testharness.inmemory.NoopPlatformTransactionManager;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.inmemory.sales.InMemoryCustomerLookup;
import com.northwood.testharness.inmemory.sales.InMemoryProductCardLookup;
import com.northwood.testharness.inmemory.sales.InMemorySalesOrderFulfilmentSagaPort;
import com.northwood.testharness.inmemory.sales.InMemorySalesOrderHeaderStatusProjection;
import com.northwood.testharness.inmemory.sales.InMemorySalesOrderLineSnapshotPort;
import com.northwood.testharness.inmemory.sales.InMemorySalesOrderRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-service test composition for sales. Wires {@link SalesOrderService},
 * the saga manager + worker shell, the compensation emitter, and every
 * inbox handler with in-memory adapters and registers them on the shared
 * {@link SynchronousBus}.
 *
 * <p>Saga-worker driving: {@link #advanceSagaWorker()} runs one drain pass
 * via the production worker shell. Used after seeding sagas (e.g. via
 * {@code placeOrder}) to step the saga forward without a real scheduler.
 */
public final class SalesTestKit {

    public final InMemoryOutboxPort outbox = new InMemoryOutboxPort();
    public final InMemoryInboxPort inbox = new InMemoryInboxPort();

    public final InMemorySalesOrderRepository orders;
    public final InMemorySalesOrderFulfilmentSagaPort sagas = new InMemorySalesOrderFulfilmentSagaPort();
    public final InMemoryCustomerLookup customers = new InMemoryCustomerLookup();
    public final InMemoryProductCardLookup productCards = new InMemoryProductCardLookup();
    public final InMemorySalesOrderHeaderStatusProjection statusProjection = new InMemorySalesOrderHeaderStatusProjection();
    public final InMemorySalesOrderLineSnapshotPort lineSnapshots;

    public final JdbcSalesOrderFulfilmentSagaManager sagaManager;
    public final SalesOrderFulfilmentSagaWorker sagaWorker;
    public final SalesOrderCompensationEmitter compensationEmitter;
    public final SalesOrderService service;

    private final String workerId = "sales.fulfilment-test-worker";

    public SalesTestKit(SynchronousBus bus, ObjectMapper json) {
        this.orders = new InMemorySalesOrderRepository(outbox, json);
        this.lineSnapshots = new InMemorySalesOrderLineSnapshotPort(orders);
        PlatformTransactionManager txm = new NoopPlatformTransactionManager();
        this.sagaManager = new JdbcSalesOrderFulfilmentSagaManager(sagas, json, txm, 30L, 15L);
        OutboxAppender appender = new OutboxAppender(outbox, json, new CurrentUserAccessor());
        this.sagaWorker = new SalesOrderFulfilmentSagaWorker(sagaManager, lineSnapshots, appender, json);
        this.compensationEmitter = new SalesOrderCompensationEmitter(orders, appender);
        this.service = new SalesOrderService(orders, sagaManager, customers, productCards);

        bus.register(outbox);
        bus.register(new StockReservedHandler(inbox, sagaManager, statusProjection, json));
        bus.register(new WorkOrderCreatedHandler(inbox, sagaManager, json));
        bus.register(new WorkOrderManufacturingCompletedHandler(inbox, sagaManager, json));
        bus.register(new ManufacturingDispatchedHandler(inbox, sagaManager, statusProjection, orders, appender, json));
        bus.register(new ShipmentPostedHandler(inbox, sagaManager, service, json));
        bus.register(new CustomerInvoiceCreatedHandler(inbox, sagaManager, json));
        bus.register(new CustomerPaymentReceivedHandler(inbox, sagaManager, statusProjection, json));
        bus.register(new InventoryCancellationAppliedHandler(inbox, sagaManager, compensationEmitter, json));
        bus.register(new ManufacturingCancellationAppliedHandler(inbox, sagaManager, compensationEmitter, json));
    }

    public UUID placeOrder(PlaceOrderCommand cmd) {
        return service.placeOrder(cmd).id();
    }

    public void cancel(UUID salesOrderHeaderId, String reason) {
        service.cancel(new CancelOrderCommand(salesOrderHeaderId, reason));
    }

    public Optional<SalesOrderFulfilmentSaga> findSagaBySalesOrderId(UUID salesOrderId) {
        return sagas.findBySalesOrderId(salesOrderId);
    }

    public Optional<SalesOrder.Status> orderStatus(UUID salesOrderId) {
        return statusProjection.get(salesOrderId);
    }

    /**
     * Drive the sales fulfilment saga worker through one drain pass. Picks
     * up sagas in {@code started} or {@code stock_reserved} and advances
     * each by one transition (emitting StockReservationRequested or
     * ManufacturingRequested respectively).
     */
    public void advanceSagaWorker() {
        sagaWorker.drainOnce(workerId);
    }
}
