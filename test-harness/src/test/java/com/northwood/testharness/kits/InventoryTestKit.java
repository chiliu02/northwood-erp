package com.northwood.testharness.kits;

import com.northwood.inventory.application.ShipmentService;
import com.northwood.inventory.application.StockReservationService;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.application.inbox.RawMaterialReservationRequestedHandler;
import com.northwood.inventory.application.inbox.SalesOrderCancellationRequestedHandler;
import com.northwood.inventory.application.inbox.SalesOrderPlacedHandler;
import com.northwood.inventory.application.inbox.StockReservationRequestedHandler;
import com.northwood.inventory.application.inbox.SubAssembliesConsumedHandler;
import com.northwood.inventory.application.inbox.WorkOrderCancelledHandler;
import com.northwood.inventory.application.inbox.WorkOrderManufacturingCompletedHandler;
import com.northwood.testharness.inmemory.InMemoryInboxPort;
import com.northwood.testharness.inmemory.InMemoryOutboxPort;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.inmemory.inventory.InMemorySalesOrderLineFactsProjection;
import com.northwood.testharness.inmemory.inventory.InMemoryShipmentRepository;
import com.northwood.testharness.inmemory.inventory.InMemoryStockBalances;
import com.northwood.testharness.inmemory.inventory.InMemoryStockMovementWriter;
import com.northwood.testharness.inmemory.inventory.InMemoryStockReservationRepository;
import com.northwood.testharness.inmemory.inventory.InMemoryWarehouseLookup;
import com.northwood.testharness.inmemory.inventory.InMemoryWipBalanceWriter;
import java.math.BigDecimal;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-service test composition for inventory. Wires
 * {@link StockReservationService} + {@link ShipmentService} and registers
 * the inbox handlers (stock-reservation, sales-cancel, work-order-cancelled,
 * WIP-bump on WO completion, WIP-decrement on sub-assembly consume, plus
 * sales-order-line-facts projection) on the bus.
 *
 * <p>Goods-receipt path isn't registered yet — it'll land with the slice
 * that needs a {@code GoodsReceiptRepository} in-memory adapter.
 */
public final class InventoryTestKit {

    public static final UUID DEFAULT_WAREHOUSE_ID = UUID.randomUUID();

    public final InMemoryOutboxPort outbox = new InMemoryOutboxPort();
    public final InMemoryInboxPort inbox = new InMemoryInboxPort();

    public final InMemoryStockReservationRepository reservations;
    public final InMemoryShipmentRepository shipments;
    public final InMemoryStockBalances stockBalances = new InMemoryStockBalances();
    public final InMemoryWarehouseLookup warehouses = new InMemoryWarehouseLookup();
    public final InMemoryWipBalanceWriter wipBalances = new InMemoryWipBalanceWriter();
    public final InMemoryStockMovementWriter stockMovements = new InMemoryStockMovementWriter();
    public final InMemorySalesOrderLineFactsProjection salesOrderLineFacts = new InMemorySalesOrderLineFactsProjection();
    public final StockReservationService service;
    public final ShipmentService shipmentService;

    public InventoryTestKit(SynchronousBus bus, ObjectMapper json) {
        this.reservations = new InMemoryStockReservationRepository(outbox, json);
        this.shipments = new InMemoryShipmentRepository(outbox, json);
        this.warehouses.put(WarehouseCodes.MAIN, DEFAULT_WAREHOUSE_ID);
        this.service = new StockReservationService(
            reservations, stockBalances, stockBalances, warehouses, outbox, json
        );
        this.shipmentService = new ShipmentService(
            shipments, stockBalances, stockMovements, warehouses, salesOrderLineFacts
        );

        bus.register(outbox);
        bus.register(new StockReservationRequestedHandler(inbox, service, json));
        bus.register(new RawMaterialReservationRequestedHandler(inbox, service, json));
        bus.register(new SalesOrderCancellationRequestedHandler(inbox, service, json));
        bus.register(new WorkOrderCancelledHandler(inbox, service, json));
        bus.register(new WorkOrderManufacturingCompletedHandler(
            inbox, json, stockBalances, wipBalances, warehouses, stockMovements
        ));
        bus.register(new SubAssembliesConsumedHandler(inbox, wipBalances, json));
        bus.register(new SalesOrderPlacedHandler(inbox, salesOrderLineFacts, json));
    }

    /** Seed enough stock so a reservation will succeed. */
    public InventoryTestKit seedStock(UUID productId, BigDecimal onHand) {
        stockBalances.seedOnHand(DEFAULT_WAREHOUSE_ID, productId, onHand);
        return this;
    }
}
