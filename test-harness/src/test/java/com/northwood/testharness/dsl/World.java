package com.northwood.testharness.dsl;

import com.northwood.finance.application.dto.RecordCustomerPaymentCommand;
import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.Payment;
import com.northwood.inventory.application.dto.PostShipmentCommand;
import com.northwood.inventory.application.dto.ShipmentLineRequest;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderLine;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.domain.Currencies;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.kits.FinanceTestKit;
import com.northwood.testharness.kits.InventoryTestKit;
import com.northwood.testharness.kits.SalesTestKit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * The single mutable fixture behind the acceptance-test DSL (see
 * {@code docs/dsl.md}). It constructs the {@link SynchronousBus} + the three
 * service kits exactly as the hand-written {@code o2c/*Test}s do, owns a
 * <strong>registry</strong> mapping business identifiers (customer codes,
 * product codes, order numbers) to the UUIDs the engine generates, and folds
 * the scattered {@code advanceSagaWorker()} / {@code bus.drain()} plumbing into
 * one faithful primitive — {@link #settle()}.
 *
 * <p><b>Slice 1 — the spike.</b> No fluent {@code given/when/then} sugar yet:
 * the methods here are plain imperative seed / action / resolver calls. Their
 * job is to prove that {@code settle()} reproduces the happy path's saga
 * progression with <em>zero</em> hand-placed drains
 * ({@code OrderToCashHappyPathWorldTest}). The fluent builders (§5/§7 of the
 * doc) land in slice 2 on top of this World unchanged.
 *
 * <p><b>Faithfulness invariant.</b> The World abstracts <em>naming and
 * timing</em> only. Every action drives the <em>real</em> application service
 * ({@code SalesOrderService}, {@code ShipmentService}, {@code PaymentService}),
 * the <em>real</em> saga manager + worker shell, and the <em>real</em> inbox
 * handlers, with events round-tripping through the <em>real</em> Jackson 3
 * serde on the bus. It forges no events and shortcuts no saga transition. If
 * {@code settle()} ever reached a state the production worker would not, the
 * tests become fiction — that is the line to guard in review.
 */
public final class World {

    /** Currency every value defaults to in the spike — the o2c suite is single-currency. */
    public static final String CURRENCY = Currencies.AUD;

    public final SynchronousBus bus;
    public final SalesTestKit sales;
    public final InventoryTestKit inventory;
    public final FinanceTestKit finance;

    // ── the registry: business identifier → engine identity / facts ──
    private final Map<String, SeededProduct> productsByCode = new HashMap<>();
    private final Map<String, UUID> orderIdsByNumber = new HashMap<>();

    public World() {
        ObjectMapper json = new ObjectMapper();
        this.bus = new SynchronousBus();
        this.sales = new SalesTestKit(bus, json);
        this.inventory = new InventoryTestKit(bus, json);
        this.finance = new FinanceTestKit(bus, json);
    }

    // ============================================================
    // Given — seed the world (the guard)
    // ============================================================

    /** Seed an active customer; the kit's lookup mints + indexes the customerId by code. */
    public World seedCustomer(String customerCode, String customerName) {
        sales.customers.put(customerCode, customerName, Customer.Status.ACTIVE);
        return this;
    }

    /**
     * Seed a priced product. Mints the productId (the engine's identity is a
     * UUID; the catalog lookup is keyed by it, not by code), seeds the sales
     * price card, and registers {@code code → product facts} so later actions
     * can resolve the code to the UUID + the sku/name an order or shipment line
     * needs.
     */
    public World seedProduct(String productCode, String productName, BigDecimal salesPrice) {
        UUID productId = UUID.randomUUID();
        sales.productCards.put(productId, salesPrice, CURRENCY);
        productsByCode.put(productCode, new SeededProduct(productId, productCode, productName, salesPrice));
        return this;
    }

    /** Seed on-hand stock at the default ({@link WarehouseCodes#MAIN}) warehouse. */
    public World seedStock(String productCode, BigDecimal onHand) {
        inventory.seedStock(product(productCode).productId(), onHand);
        return this;
    }

    // ============================================================
    // When — drive a real domain action, then settle (the trigger)
    // ============================================================

    /**
     * Place an order through the real {@code SalesOrderService}, register
     * {@code orderNumber → salesOrderId}, then {@link #settle()}. Lines are
     * {@code (productCode, qty)}; unit price + tax are left null so the service
     * prices each line off the catalog card (matching the hand-written test).
     */
    public World placeOrder(String orderNumber, String customerCode, List<OrderLineSpec> lines) {
        List<OrderLine> commandLines = new ArrayList<>();
        for (OrderLineSpec spec : lines) {
            SeededProduct p = product(spec.productCode());
            commandLines.add(new OrderLine(
                p.productId(), p.code(), p.name(), spec.quantity(), null, BigDecimal.ZERO));
        }
        UUID orderId = sales.placeOrder(new PlaceOrderCommand(
            orderNumber, customerCode, LocalDate.of(2026, 5, 20), CURRENCY, null, commandLines));
        orderIdsByNumber.put(orderNumber, orderId);
        settle();
        return this;
    }

    /**
     * Post a shipment for an order through the real {@code ShipmentService},
     * then {@link #settle()}. Resolves the order's customer + per-line
     * {@code salesOrderLineId} out of the registry / sales aggregate so the
     * caller names only business facts ({@code (productCode, qty, unitCost)}).
     */
    public World ship(String shipmentNumber, String orderNumber, List<ShipLineSpec> lines) {
        UUID orderId = orderId(orderNumber);
        SalesOrder order = sales.orders.findById(SalesOrderId.of(orderId)).orElseThrow();

        List<ShipmentLineRequest> requestLines = new ArrayList<>();
        for (ShipLineSpec spec : lines) {
            SeededProduct p = product(spec.productCode());
            SalesOrderLine line = order.lines().stream()
                .filter(l -> p.productId().equals(l.productId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "Order " + orderNumber + " has no line for product " + spec.productCode()));
            requestLines.add(new ShipmentLineRequest(
                line.lineId(), p.productId(), p.code(), p.name(), spec.quantity(), spec.unitCost()));
        }

        inventory.shipmentService.post(new PostShipmentCommand(
            shipmentNumber, orderId, orderNumber,
            order.customerId(), order.customerName(),
            WarehouseCodes.MAIN, requestLines));
        settle();
        return this;
    }

    /**
     * Record a customer payment against an order's commercial invoice through
     * the real {@code PaymentService}, then {@link #settle()}. Resolves the
     * invoice header id from finance by walking back from the order number.
     */
    public World pay(String paymentNumber, String orderNumber, BigDecimal amount) {
        UUID invoiceHeaderId = commercialInvoice(orderNumber)
            .orElseThrow(() -> new IllegalStateException(
                "No commercial invoice for order " + orderNumber + " — cannot pay"))
            .id().value();
        finance.paymentService.recordCustomerPayment(new RecordCustomerPaymentCommand(
            paymentNumber, invoiceHeaderId, amount,
            Payment.Method.BANK_TRANSFER.dbValue(), LocalDate.of(2026, 5, 20)));
        settle();
        return this;
    }

    // ============================================================
    // settle() — the one faithfulness primitive
    // ============================================================

    /**
     * Run the asynchronous machinery to quiescence. Alternates the <em>real</em>
     * {@code SalesOrderFulfilmentSagaWorker} shell (one drain pass — advances
     * any saga the production worker would pick up) with the <em>real</em>
     * {@code SynchronousBus} (cascades handlers + cross-service events, same
     * serde as production), until a full worker+drain cycle produces no new
     * outbox rows.
     *
     * <p>The fixed-point detector is the union outbox-row count across the three
     * kits. {@code InMemoryOutboxPort} never removes a row (publish only flips
     * status), so the count is monotonic: a cycle that appends nothing means
     * neither the worker nor any handler had more to do, and a saga deliberately
     * parked to a future instant (e.g. {@code awaiting_release}) is correctly
     * <em>not</em> spun on — it parks beyond {@code now} so the worker stops
     * claiming it, the row count stabilises, and {@code settle()} returns.
     *
     * <p>This is the <em>only</em> place infrastructure timing lives. Tests
     * never call {@code advanceSagaWorker()} or {@code bus.drain()} directly;
     * every {@code when} action settles for them.
     */
    public void settle() {
        int safety = 1000;
        int previousRows;
        do {
            if (safety-- <= 0) {
                throw new IllegalStateException("settle() did not converge in 1000 cycles — infinite cascade?");
            }
            previousRows = totalOutboxRows();
            sales.advanceSagaWorker();
            bus.drain();
        } while (totalOutboxRows() != previousRows);
    }

    private int totalOutboxRows() {
        return sales.outbox.size() + inventory.outbox.size() + finance.outbox.size();
    }

    // ============================================================
    // Then — resolve the outcome by business identifier
    // ============================================================

    /** The fulfilment saga's current state for an order (e.g. {@code "ready_to_ship"}). */
    public String sagaState(String orderNumber) {
        return sales.findSagaBySalesOrderId(orderId(orderNumber)).orElseThrow().state();
    }

    /** The order header's status fold. */
    public Optional<SalesOrder.Status> orderStatus(String orderNumber) {
        return sales.orderStatus(orderId(orderNumber));
    }

    /** The COMMERCIAL invoice raised for an order, if finance has created one. */
    public Optional<CustomerInvoice> commercialInvoice(String orderNumber) {
        UUID orderId = orderId(orderNumber);
        return finance.customerInvoices.findAll().stream()
            .filter(inv -> orderId.equals(inv.salesOrderHeaderId()))
            .filter(inv -> inv.invoiceType() == CustomerInvoice.InvoiceType.COMMERCIAL)
            .findFirst();
    }

    /** Union of every kit's published + pending outbox event types — the {@code events_published(…)} basis. */
    public Set<String> publishedEventTypes() {
        Set<String> types = new LinkedHashSet<>();
        for (var kitOutbox : List.of(sales.outbox, inventory.outbox, finance.outbox)) {
            kitOutbox.all().stream().map(OutboxRow::getEventType).forEach(types::add);
        }
        return types;
    }

    // ============================================================
    // Registry resolution
    // ============================================================

    private SeededProduct product(String productCode) {
        SeededProduct p = productsByCode.get(productCode);
        if (p == null) {
            throw new IllegalStateException("Unknown product code: " + productCode + " — seed it first with seedProduct(...)");
        }
        return p;
    }

    private UUID orderId(String orderNumber) {
        UUID id = orderIdsByNumber.get(orderNumber);
        if (id == null) {
            throw new IllegalStateException("Unknown order number: " + orderNumber + " — place it first with placeOrder(...)");
        }
        return id;
    }

    // ============================================================
    // Line specs + registry record
    // ============================================================

    /** An order line named in business terms: a product code + quantity. */
    public record OrderLineSpec(String productCode, BigDecimal quantity) {}

    /** A shipment line named in business terms: a product code, quantity, and unit cost. */
    public record ShipLineSpec(String productCode, BigDecimal quantity, BigDecimal unitCost) {}

    /** A seeded product's engine identity + the facts an order / shipment line needs. */
    private record SeededProduct(UUID productId, String code, String name, BigDecimal salesPrice) {}
}
