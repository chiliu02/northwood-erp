package com.northwood.testharness.kits;

import com.northwood.manufacturing.application.BomService;
import com.northwood.manufacturing.application.MaterialsCostRollupService;
import com.northwood.manufacturing.application.WorkOrderOperationService;
import com.northwood.manufacturing.application.WorkOrderPrioritisationService;
import com.northwood.manufacturing.application.WorkOrderReleaseService;
import com.northwood.manufacturing.application.inbox.ApprovedVendorListChangedHandler;
import com.northwood.manufacturing.application.inbox.ActiveBomChangedHandler;
import com.northwood.manufacturing.application.inbox.GoodsReceivedHandler;
import com.northwood.manufacturing.application.inbox.MakeVsBuyChangedHandler;
import com.northwood.manufacturing.application.inbox.ProductCreatedHandler;
import com.northwood.manufacturing.application.inbox.ProductDiscontinuedHandler;
import com.northwood.manufacturing.application.inbox.RawMaterialsReservedHandler;
import com.northwood.manufacturing.application.inbox.ReplenishmentRequestedHandler;
import com.northwood.manufacturing.application.inbox.SupplierProductPriceChangedHandler;
import com.northwood.manufacturing.application.saga.RawMaterialReservationRequestEmitter;
import com.northwood.manufacturing.infrastructure.saga.JdbcWorkOrderSagaManager;
import com.northwood.manufacturing.infrastructure.saga.WorkOrderSagaWorker;
import com.northwood.shared.application.outbox.OutboxAppender;
import com.northwood.shared.application.security.CurrentUserAccessor;
import com.northwood.testharness.inmemory.InMemoryInboxPort;
import com.northwood.testharness.inmemory.InMemoryOutboxPort;
import com.northwood.testharness.inmemory.NoopPlatformTransactionManager;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.inmemory.manufacturing.InMemoryBomCycleDetector;
import com.northwood.testharness.inmemory.manufacturing.InMemoryBomRepository;
import com.northwood.testharness.inmemory.manufacturing.InMemoryBomLookup;
import com.northwood.testharness.inmemory.manufacturing.InMemoryWorkOrderSagaPort;
import com.northwood.testharness.inmemory.manufacturing.InMemoryWorkOrderShortageRecoveryQueryPort;
import com.northwood.testharness.inmemory.manufacturing.InMemoryProductActiveBomProjection;
import com.northwood.testharness.inmemory.manufacturing.InMemoryProductApprovedVendorProjection;
import com.northwood.testharness.inmemory.manufacturing.InMemoryProductMaterialsCostProjection;
import com.northwood.testharness.inmemory.manufacturing.InMemoryProductReplenishmentProjection;
import com.northwood.testharness.inmemory.manufacturing.InMemoryRoutingQueryPort;
import com.northwood.testharness.inmemory.manufacturing.InMemoryWorkCenterRateLookup;
import com.northwood.testharness.inmemory.manufacturing.InMemoryWorkOrderRepository;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-service test composition for manufacturing. Wires every application
 * service the production wiring uses, plus the work-order saga manager,
 * saga worker shell, and 9 inbox handlers, against in-memory adapters.
 *
 * <p>Saga-worker driving: {@link #advanceSagaWorker()} runs one drain pass
 * via the production worker shell. Test code calls it after seeding sagas
 * (the release service seeds them at {@code work_order_created} as it releases
 * each stock-replenishment work order).
 */
public final class ManufacturingTestKit {

    public final InMemoryOutboxPort outbox = new InMemoryOutboxPort();
    public final InMemoryInboxPort inbox = new InMemoryInboxPort();

    public final InMemoryWorkOrderRepository workOrders;
    public final InMemoryRoutingQueryPort routings = new InMemoryRoutingQueryPort();
    public final InMemoryWorkCenterRateLookup workCenterRates = new InMemoryWorkCenterRateLookup();
    public final InMemoryBomLookup bomLookup = new InMemoryBomLookup();
    public final InMemoryBomRepository boms;
    public final InMemoryBomCycleDetector bomCycleDetector = new InMemoryBomCycleDetector(bomLookup);
    public final InMemoryWorkOrderSagaPort sagas = new InMemoryWorkOrderSagaPort();
    public final InMemoryProductReplenishmentProjection replenishment = new InMemoryProductReplenishmentProjection();
    public final InMemoryProductActiveBomProjection activeBoms = new InMemoryProductActiveBomProjection();
    public final InMemoryProductApprovedVendorProjection approvedVendors = new InMemoryProductApprovedVendorProjection();
    public final InMemoryProductMaterialsCostProjection materialsCosts = new InMemoryProductMaterialsCostProjection();
    public final InMemoryWorkOrderShortageRecoveryQueryPort shortageRecovery;

    public final JdbcWorkOrderSagaManager sagaManager;
    public final WorkOrderSagaWorker sagaWorker;
    public final WorkOrderReleaseService releaseService;
    public final WorkOrderOperationService operationService;
    public final WorkOrderPrioritisationService prioritisationService;
    public final MaterialsCostRollupService rollupService;
    public final BomService bomService;

    private final String workerId;

    public ManufacturingTestKit(SynchronousBus bus, ObjectMapper json) {
        this.workOrders = new InMemoryWorkOrderRepository(outbox, json);
        this.boms = new InMemoryBomRepository(outbox, json);
        this.shortageRecovery = new InMemoryWorkOrderShortageRecoveryQueryPort(sagas, workOrders);
        PlatformTransactionManager txm = new NoopPlatformTransactionManager();
        this.sagaManager = new JdbcWorkOrderSagaManager(sagas, json, txm, 30L, 15L);
        this.releaseService = new WorkOrderReleaseService(workOrders, routings, bomLookup, sagaManager);

        CurrentUserAccessor currentUser = new CurrentUserAccessor();
        OutboxAppender appender = new OutboxAppender(outbox, json, currentUser);
        this.operationService = new WorkOrderOperationService(
            workOrders, sagaManager, appender
        );
        this.prioritisationService = new WorkOrderPrioritisationService(
            workOrders, appender
        );
        this.rollupService = new MaterialsCostRollupService(
            replenishment, approvedVendors, materialsCosts, bomLookup, routings, workCenterRates, appender
        );
        this.bomService = new BomService(boms, bomCycleDetector, rollupService, replenishment);

        RawMaterialReservationRequestEmitter reservationEmitter =
            new RawMaterialReservationRequestEmitter(workOrders, appender);
        this.sagaWorker = new WorkOrderSagaWorker(
            sagaManager, reservationEmitter
        );
        this.workerId = "manufacturing.mto-test-worker";

        bus.register(outbox);
        bus.register(new RawMaterialsReservedHandler(inbox, sagaManager, workOrders, appender, json));
        bus.register(new GoodsReceivedHandler(inbox, sagaManager, shortageRecovery, reservationEmitter, json));
        bus.register(new ActiveBomChangedHandler(inbox, activeBoms, rollupService, json));
        bus.register(new MakeVsBuyChangedHandler(inbox, replenishment, json));
        bus.register(new ProductCreatedHandler(inbox, replenishment, json));
        bus.register(new ProductDiscontinuedHandler(inbox, replenishment, activeBoms, bomLookup, json));
        bus.register(new SupplierProductPriceChangedHandler(inbox, rollupService, json));
        bus.register(new ApprovedVendorListChangedHandler(inbox, approvedVendors, json));

        // Manufacturing's dispatcher for stock-replenishment requests routed by
        // inventory's detection-trigger.
        bus.register(new ReplenishmentRequestedHandler(inbox, releaseService, bomLookup, appender, json));
    }

    /**
     * Drive the work-order saga worker through one drain pass. Picks up
     * sagas in {@code work_order_created} (advancing each by one transition).
     * The {@code started} entry state was removed — every saga is now
     * seeded directly at {@code work_order_created} by the release service.
     */
    public void advanceSagaWorker() {
        sagaWorker.drainOnce(workerId);
    }
}
