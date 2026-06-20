package com.northwood.testharness.kits;

import com.northwood.purchasing.application.PurchaseOrderService;
import com.northwood.purchasing.application.PurchaseRequisitionService;
import com.northwood.purchasing.application.SupplierProductPriceService;
import com.northwood.purchasing.application.inbox.ApprovedVendorListChangedHandler;
import com.northwood.purchasing.application.inbox.GoodsReceivedHandler;
import com.northwood.purchasing.application.inbox.MakeVsBuyChangedHandler;
import com.northwood.purchasing.application.inbox.OrderPeggedSupplyCancellationRequestedHandler;
import com.northwood.purchasing.application.inbox.ProductDiscontinuedHandler;
import com.northwood.purchasing.application.inbox.ReplenishmentRequestedHandler;
import com.northwood.purchasing.application.inbox.ReplenishmentStrategyChangedHandler;
import com.northwood.purchasing.application.inbox.SupplierInvoiceApprovedHandler;
import com.northwood.purchasing.application.inbox.SupplierInvoiceRejectedHandler;
import com.northwood.purchasing.application.inbox.SupplierPaymentMadeHandler;
import com.northwood.purchasing.infrastructure.saga.JdbcPurchaseToPaySagaManager;
import com.northwood.purchasing.infrastructure.saga.PurchaseToPaySagaWorker;
import com.northwood.shared.application.outbox.OutboxAppender;
import com.northwood.shared.application.security.CurrentUserAccessor;
import com.northwood.testharness.inmemory.InMemoryInboxPort;
import com.northwood.testharness.inmemory.InMemoryOutboxPort;
import com.northwood.testharness.inmemory.NoopPlatformTransactionManager;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.inmemory.purchasing.InMemoryApprovedVendorQueryPort;
import com.northwood.testharness.inmemory.purchasing.InMemoryDiscontinuedProductLookup;
import com.northwood.testharness.inmemory.purchasing.InMemoryProductApprovedVendorProjection;
import com.northwood.testharness.inmemory.purchasing.InMemoryPurchasableProductLookup;
import com.northwood.testharness.inmemory.purchasing.InMemoryToOrderProductLookup;
import com.northwood.testharness.inmemory.purchasing.InMemoryPurchaseOrderPaymentProjection;
import com.northwood.testharness.inmemory.purchasing.InMemoryPurchaseOrderReceiptProjection;
import com.northwood.testharness.inmemory.purchasing.InMemoryPurchaseOrderRepository;
import com.northwood.testharness.inmemory.purchasing.InMemoryPurchaseRequisitionRepository;
import com.northwood.testharness.inmemory.purchasing.InMemoryPurchaseToPaySagaPort;
import com.northwood.testharness.inmemory.purchasing.InMemorySupplierProductPriceLookup;
import com.northwood.testharness.inmemory.purchasing.InMemorySupplierProductPriceRepository;
import com.northwood.testharness.inmemory.purchasing.InMemorySupplierRepository;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-service test composition for purchasing. Wires the application
 * services + the purchase-to-pay saga manager + worker shell + 5 inbox
 * handlers against in-memory adapters.
 *
 * <p>Saga-worker driving: {@link #advanceSagaWorker()} runs one drain pass
 * via the production worker shell. Picks up sagas in
 * {@code purchase_order_approved} and parks them at {@code waiting_for_goods}.
 *
 * <p>The kit pre-seeds one active default supplier (SUP-001) so
 * {@code PurchaseRequisitionService.createForStockReplenishment} works out of
 * the box. Tests that need richer supplier setup add via
 * {@link InMemorySupplierRepository#putActive}.
 */
public final class PurchasingTestKit {

    public final InMemoryOutboxPort outbox = new InMemoryOutboxPort();
    public final InMemoryInboxPort inbox = new InMemoryInboxPort();

    public final InMemoryPurchaseOrderRepository orders;
    public final InMemoryPurchaseRequisitionRepository requisitions;
    public final InMemorySupplierRepository suppliers = new InMemorySupplierRepository();
    public final InMemoryPurchaseToPaySagaPort sagas = new InMemoryPurchaseToPaySagaPort();
    public final InMemoryApprovedVendorQueryPort approvedVendorQuery = new InMemoryApprovedVendorQueryPort();
    public final InMemorySupplierProductPriceLookup priceLookup = new InMemorySupplierProductPriceLookup();
    public final InMemorySupplierProductPriceRepository priceRepository;
    public final InMemoryProductApprovedVendorProjection approvedVendorProjection = new InMemoryProductApprovedVendorProjection();
    public final InMemoryDiscontinuedProductLookup discontinuedProducts = new InMemoryDiscontinuedProductLookup();
    public final InMemoryPurchasableProductLookup purchasableProducts = new InMemoryPurchasableProductLookup();
    public final InMemoryToOrderProductLookup toOrderProducts = new InMemoryToOrderProductLookup();
    public final InMemoryPurchaseOrderReceiptProjection receiptProjection;
    public final InMemoryPurchaseOrderPaymentProjection paymentProjection = new InMemoryPurchaseOrderPaymentProjection();

    public final JdbcPurchaseToPaySagaManager sagaManager;
    public final PurchaseToPaySagaWorker sagaWorker;
    public final PurchaseOrderService purchaseOrderService;
    public final PurchaseRequisitionService requisitionService;
    public final SupplierProductPriceService priceService;

    private final String workerId = "purchasing.p2p-test-worker";

    public PurchasingTestKit(SynchronousBus bus, ObjectMapper json) {
        this.orders = new InMemoryPurchaseOrderRepository(outbox, json);
        this.requisitions = new InMemoryPurchaseRequisitionRepository(outbox, json);
        this.priceRepository = new InMemorySupplierProductPriceRepository(outbox, json);
        this.receiptProjection = new InMemoryPurchaseOrderReceiptProjection(orders);

        // Seed a default supplier so shortage-driven requisitions have somewhere to land.
        this.suppliers.putActive("SUP-001", "Default Supplier");

        PlatformTransactionManager txm = new NoopPlatformTransactionManager();
        this.sagaManager = new JdbcPurchaseToPaySagaManager(sagas, txm, 30L, 15L);

        CurrentUserAccessor currentUser = new CurrentUserAccessor();
        this.purchaseOrderService = new PurchaseOrderService(
            orders, requisitions, suppliers, sagaManager, priceLookup, approvedVendorQuery, currentUser
        );
        this.requisitionService = new PurchaseRequisitionService(
            requisitions, suppliers, purchaseOrderService, discontinuedProducts, purchasableProducts,
            toOrderProducts, currentUser, true
        );

        // No-op query port — the harness drives setPrice, not the enriched list view.
        this.priceService = new SupplierProductPriceService(priceRepository, () -> java.util.List.of());

        this.sagaWorker = new PurchaseToPaySagaWorker(sagaManager);

        OutboxAppender appender = new OutboxAppender(outbox, json, currentUser);

        bus.register(outbox);
        bus.register(new ReplenishmentRequestedHandler(inbox, requisitionService, appender, json));
        bus.register(new OrderPeggedSupplyCancellationRequestedHandler(inbox, purchaseOrderService, appender, json));
        bus.register(new GoodsReceivedHandler(inbox, sagaManager, receiptProjection, json));
        bus.register(new SupplierInvoiceApprovedHandler(inbox, sagaManager, paymentProjection, json));
        bus.register(new SupplierInvoiceRejectedHandler(inbox, sagaManager, json));
        bus.register(new SupplierPaymentMadeHandler(inbox, sagaManager, paymentProjection, json));
        bus.register(new ApprovedVendorListChangedHandler(inbox, approvedVendorProjection, json));
        bus.register(new ProductDiscontinuedHandler(inbox, discontinuedProducts, json));
        bus.register(new MakeVsBuyChangedHandler(inbox, purchasableProducts, json));
        bus.register(new ReplenishmentStrategyChangedHandler(inbox, toOrderProducts, json));
    }

    public void advanceSagaWorker() {
        // PurchaseToPaySagaWorker.poll() is the production cadence driver and
        // already calls manager.drain(...). Test-side it's safe to invoke
        // directly; the `@Scheduled` annotation isn't active without a
        // ScheduledExecutorService in context.
        sagaWorker.poll();
    }
}
