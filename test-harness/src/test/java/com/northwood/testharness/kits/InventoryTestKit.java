package com.northwood.testharness.kits;

import com.northwood.inventory.application.StockReservationService;
import com.northwood.inventory.application.inbox.RawMaterialReservationRequestedHandler;
import com.northwood.inventory.application.inbox.SalesOrderCancellationRequestedHandler;
import com.northwood.inventory.application.inbox.StockReservationRequestedHandler;
import com.northwood.inventory.application.inbox.SubAssembliesConsumedHandler;
import com.northwood.inventory.application.inbox.WorkOrderCancelledHandler;
import com.northwood.inventory.application.inbox.WorkOrderManufacturingCompletedHandler;
import com.northwood.testharness.inmemory.InMemoryInboxPort;
import com.northwood.testharness.inmemory.InMemoryOutboxPort;
import com.northwood.testharness.inmemory.SynchronousBus;
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
 * {@link StockReservationService} + the inbox handlers (stock-reservation,
 * sales-cancel, work-order-cancelled, WIP-bump on WO completion, WIP-decrement
 * on sub-assembly consume) with in-memory ports and registers them on the bus.
 *
 * <p>Goods-receipt / shipment paths aren't registered yet — they'll land
 * with the slices that need {@code GoodsReceiptRepository} and
 * {@code ShipmentRepository} in-memory adapters.
 */
public final class InventoryTestKit {

    public static final UUID DEFAULT_WAREHOUSE_ID = UUID.randomUUID();

    public final InMemoryOutboxPort outbox = new InMemoryOutboxPort();
    public final InMemoryInboxPort inbox = new InMemoryInboxPort();

    public final InMemoryStockReservationRepository reservations;
    public final InMemoryStockBalances stockBalances = new InMemoryStockBalances();
    public final InMemoryWarehouseLookup warehouses = new InMemoryWarehouseLookup();
    public final InMemoryWipBalanceWriter wipBalances = new InMemoryWipBalanceWriter();
    public final InMemoryStockMovementWriter stockMovements = new InMemoryStockMovementWriter();
    public final StockReservationService service;

    public InventoryTestKit(SynchronousBus bus, ObjectMapper json) {
        this.reservations = new InMemoryStockReservationRepository(outbox, json);
        this.warehouses.put("MAIN", DEFAULT_WAREHOUSE_ID);
        this.service = new StockReservationService(
            reservations, stockBalances, stockBalances, warehouses, outbox, json
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
    }

    /** Seed enough stock so a reservation will succeed. */
    public InventoryTestKit seedStock(UUID productId, BigDecimal onHand) {
        stockBalances.seedOnHand(DEFAULT_WAREHOUSE_ID, productId, onHand);
        return this;
    }
}
