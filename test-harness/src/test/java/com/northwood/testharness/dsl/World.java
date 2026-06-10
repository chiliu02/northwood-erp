package com.northwood.testharness.dsl;

import com.northwood.finance.application.dto.RecordCustomerPaymentCommand;
import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.JournalEntry;
import com.northwood.finance.domain.Payment;
import com.northwood.inventory.application.dto.PostShipmentCommand;
import com.northwood.inventory.application.dto.ShipmentLineRequest;
import com.northwood.inventory.application.dto.StockBalanceView;
import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.manufacturing.application.dto.CompleteOperationCommand;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.WorkOrderOperation;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.PaymentTerms;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderLine;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.domain.Currencies;
import com.northwood.testharness.inmemory.InMemoryOutboxPort;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.inmemory.manufacturing.InMemoryBomLookup;
import com.northwood.testharness.kits.FinanceTestKit;
import com.northwood.testharness.kits.InventoryTestKit;
import com.northwood.testharness.kits.ManufacturingTestKit;
import com.northwood.testharness.kits.SalesTestKit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * {@code docs/test-harness-dsl.md}). It constructs the {@link SynchronousBus} + the three
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
 * ({@code WorldTest}). The fluent builders (§5/§7 of the
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
    public final ManufacturingTestKit manufacturing;

    // ── the registry: business identifier → engine identity / facts ──
    private final Map<String, SeededProduct> productsByCode = new HashMap<>();
    private final Map<String, UUID> orderIdsByNumber = new HashMap<>();

    // settle()'s fixed point ticks every wired kit's saga worker and counts the
    // union of every wired kit's outbox, so a newly-wired kit auto-joins both.
    private final List<InMemoryOutboxPort> outboxes = new ArrayList<>();
    private final List<Runnable> sagaTickers = new ArrayList<>();

    public World() {
        ObjectMapper json = new ObjectMapper();
        this.bus = new SynchronousBus();
        this.sales = new SalesTestKit(bus, json);
        this.inventory = new InventoryTestKit(bus, json);
        this.finance = new FinanceTestKit(bus, json);
        this.manufacturing = new ManufacturingTestKit(bus, json);

        this.outboxes.addAll(List.of(
            sales.outbox, inventory.outbox, finance.outbox, manufacturing.outbox));
        // Only kits with a saga worker tick; inventory + finance have none.
        this.sagaTickers.addAll(List.of(
            sales::advanceSagaWorker, manufacturing::advanceSagaWorker));
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

    /**
     * Give a product a planning time fence of {@code fenceDays} (REQ-SAL-037).
     * The fulfilment worker then parks a far-future order of this product at
     * {@code awaiting_release} until {@code need-by − fenceDays} (read against
     * {@link #setClockAt}); within the fence it reserves immediately.
     */
    public World seedProductFence(String productCode, int fenceDays) {
        sales.lineSnapshots.withFence(product(productCode).productId(), fenceDays);
        return this;
    }

    /**
     * Set the world clock the fulfilment worker reads for the planning-time-fence
     * gate (UTC start-of-day of {@code date}). No real time passes; advancing the
     * clock is how a fenced order's release is reached deterministically.
     */
    public World setClockAt(LocalDate date) {
        sales.setClock(date.atStartOfDay(ZoneOffset.UTC).toInstant());
        return this;
    }

    // ── manufacturing: make-to-stock products, BOMs, routings, reorder policy ──

    /**
     * Seed a manufactured (make-to-stock) product: mints its id, registers it,
     * and snapshots its make-vs-buy classification (manufactured) into both
     * inventory (routes replenishment to manufacturing) and manufacturing (the
     * dispatcher's filter). No sales price card — it is replenished to stock,
     * not sold here.
     */
    public World seedManufacturedProduct(String productCode, String productName) {
        UUID productId = UUID.randomUUID();
        productsByCode.put(productCode, new SeededProduct(productId, productCode, productName, null));
        inventory.productReplenishment.put(productId, false, true);
        manufacturing.replenishment.put(productId, false, true);
        return this;
    }

    /** Seed a raw-material product (a BOM component): mints its id and registers it. */
    public World seedRawMaterial(String productCode, String productName) {
        UUID productId = UUID.randomUUID();
        productsByCode.put(productCode, new SeededProduct(productId, productCode, productName, null));
        return this;
    }

    /**
     * Give a manufactured product a single-component active BOM ({@code qtyPerUnit}
     * of {@code rawCode} per finished unit) and register the product's identity for
     * work-order release. Both products must already be seeded.
     */
    public World seedBom(String fgCode, String rawCode, BigDecimal qtyPerUnit) {
        SeededProduct fg = product(fgCode);
        SeededProduct raw = product(rawCode);
        manufacturing.bomLookup.put(fg.productId(), UUID.randomUUID(),
            InMemoryBomLookup.rawLine(raw.productId(), raw.code(), raw.name(), qtyPerUnit, BigDecimal.ZERO));
        manufacturing.bomLookup.putIdentity(fg.productId(), fg.code(), fg.name());
        return this;
    }

    /** Give a manufactured product a single-operation active routing. */
    public World seedSingleOpRouting(String fgCode) {
        manufacturing.routings.putSingleOp(product(fgCode).productId());
        return this;
    }

    /** Set a product's reorder policy (point + quantity) on inventory's local snapshot (REQ-PROD-020). */
    public World seedReorderPolicy(String productCode, BigDecimal reorderPoint, BigDecimal reorderQuantity) {
        inventory.reorderPolicies.put(product(productCode).productId(), reorderPoint, reorderQuantity);
        return this;
    }

    /**
     * Mark an already-priced product as make-to-order (REQ-INV-093): manufactured
     * make-vs-buy + order-pegged, so a sales-order line for it raises dedicated
     * order-pegged supply routed to manufacturing rather than drawing from the pool.
     */
    public World markManufacturedToOrder(String productCode) {
        UUID productId = product(productCode).productId();
        sales.lineSnapshots.markOrderPegged(productId);
        inventory.productReplenishment.put(productId, false, true);
        manufacturing.replenishment.put(productId, false, true);
        return this;
    }

    // ============================================================
    // When — drive a real domain action, then settle (the trigger)
    // ============================================================

    /**
     * Place a standard ({@code on_shipment}-terms) order through the real
     * {@code SalesOrderService}, register {@code orderNumber → salesOrderId},
     * then {@link #settle()}. Lines are {@code (productCode, qty)}; unit price +
     * tax are left null so the service prices each line off the catalog card
     * (matching the hand-written test).
     */
    public World placeOrder(String orderNumber, String customerCode, List<OrderLineSpec> lines) {
        return placeOrder(orderNumber, customerCode, null, null, lines);
    }

    /**
     * Place a standard order but <em>do not</em> {@link #settle()} — the
     * {@code .without_settling()} escape hatch (doc §6). Leaves the saga at its
     * freshly-{@code started} state with {@code SalesOrderPlaced} still pending,
     * so a test can act on the not-yet-processed order — e.g. cancel before the
     * worker reserves stock (the {@code CancelCompensationTest} branch). The
     * next settling action ({@link #cancel}) drains the parked event with it.
     */
    public World placeOrderWithoutSettling(String orderNumber, String customerCode, List<OrderLineSpec> lines) {
        return placeOrder(orderNumber, customerCode, null, null, null, lines, false);
    }

    /**
     * Place a standard order with an explicit requested-delivery (need-by) date,
     * then {@link #settle()}. The date is actionable only through the planning
     * time fence (REQ-SAL-013/037): for a product seeded with
     * {@link #seedProductFence}, the worker parks the order at
     * {@code awaiting_release} until {@code need-by − fence}, read against the
     * world clock ({@link #setClockAt}). With no fence the date is inert.
     */
    public World placeOrder(String orderNumber, String customerCode, LocalDate needBy, List<OrderLineSpec> lines) {
        return placeOrder(orderNumber, customerCode, null, null, needBy, lines, true);
    }

    /**
     * Place a deposit (part-payment) order — {@link PaymentTerms#DEPOSIT} terms
     * with the given up-front fraction (e.g. {@code 50} for 50%). The worker
     * raises a deposit invoice at placement and the saga parks at
     * {@code deposit_invoiced} until the deposit is paid ({@link #payDeposit}).
     */
    public World placeDepositOrder(
        String orderNumber, String customerCode, BigDecimal depositPercent, List<OrderLineSpec> lines) {
        return placeOrder(orderNumber, customerCode, PaymentTerms.DEPOSIT.dbValue(), depositPercent, lines);
    }

    /**
     * Place a cash-on-delivery order — {@link PaymentTerms#CASH_ON_DELIVERY}
     * terms. There is no operator payment step: at shipment the saga walks
     * straight to {@code completed} and finance auto-creates the invoice and
     * auto-records a full {@link Payment.Method#CASH} payment from the single
     * {@code SalesOrderShipped} event.
     */
    public World placeCodOrder(String orderNumber, String customerCode, List<OrderLineSpec> lines) {
        return placeOrder(orderNumber, customerCode, PaymentTerms.CASH_ON_DELIVERY.dbValue(), null, lines);
    }

    /**
     * Place an order with explicit payment terms + optional deposit fraction,
     * then {@link #settle()}. {@code paymentTerms == null} inherits the
     * customer default; {@code depositPercent} is only meaningful for
     * {@link PaymentTerms#DEPOSIT} and stays null otherwise.
     */
    public World placeOrder(
        String orderNumber, String customerCode, String paymentTerms,
        BigDecimal depositPercent, List<OrderLineSpec> lines) {
        return placeOrder(orderNumber, customerCode, paymentTerms, depositPercent, null, lines, true);
    }

    private World placeOrder(
        String orderNumber, String customerCode, String paymentTerms,
        BigDecimal depositPercent, LocalDate needBy, List<OrderLineSpec> lines, boolean settle) {
        List<OrderLine> commandLines = new ArrayList<>();
        for (OrderLineSpec spec : lines) {
            SeededProduct p = product(spec.productCode());
            commandLines.add(new OrderLine(
                p.productId(), p.code(), p.name(), spec.quantity(), null, BigDecimal.ZERO));
        }
        LocalDate deliveryDate = needBy != null ? needBy : LocalDate.of(2026, 5, 20);
        UUID orderId = sales.placeOrder(new PlaceOrderCommand(
            orderNumber, customerCode, deliveryDate,
            CURRENCY, paymentTerms, depositPercent, commandLines));
        orderIdsByNumber.put(orderNumber, orderId);
        if (settle) {
            settle();
        }
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
     * Record a customer payment against an order's COMMERCIAL invoice through
     * the real {@code PaymentService}, then {@link #settle()}. Resolves the
     * invoice header id from finance by walking back from the order number.
     */
    public World pay(String paymentNumber, String orderNumber, BigDecimal amount) {
        return payInvoice(paymentNumber, requireInvoice(orderNumber, CustomerInvoice.InvoiceType.COMMERCIAL), amount);
    }

    /**
     * Record the up-front payment against an order's DEPOSIT invoice (the
     * {@link PaymentTerms#DEPOSIT} flow), then {@link #settle()} — which drives
     * the saga {@code deposit_invoiced → deposit_paid} and on to
     * {@code ready_to_ship} once stock reserves.
     */
    public World payDeposit(String paymentNumber, String orderNumber, BigDecimal amount) {
        return payInvoice(paymentNumber, requireInvoice(orderNumber, CustomerInvoice.InvoiceType.DEPOSIT), amount);
    }

    /**
     * Record the settling payment against an order's BALANCE invoice (raised at
     * shipment on a deposit order), then {@link #settle()} — completing the order.
     */
    public World payBalance(String paymentNumber, String orderNumber, BigDecimal amount) {
        return payInvoice(paymentNumber, requireInvoice(orderNumber, CustomerInvoice.InvoiceType.BALANCE), amount);
    }

    /**
     * Record a payment against a resolved invoice through the real
     * {@code PaymentService}, {@link #settle()}, then stamp the allocation as
     * the stand-in for the {@code maintain_allocation_totals} DB trigger (which
     * the in-memory finance kit does not model — see {@code FinanceTestKit}).
     * The stamp lands after settling so a <em>later</em> payment against another
     * invoice on the same order (deposit → balance) reads this one as fully paid
     * when it computes order-level settlement; for a single-payment order it is
     * a harmless post-completion bookkeeping update.
     */
    private World payInvoice(String paymentNumber, CustomerInvoice invoice, BigDecimal amount) {
        finance.paymentService.recordCustomerPayment(new RecordCustomerPaymentCommand(
            paymentNumber, invoice.id().value(), amount,
            Payment.Method.BANK_TRANSFER.dbValue(), LocalDate.of(2026, 5, 20)));
        settle();
        finance.customerInvoices.recordAllocation(invoice.id().value(), amount);
        return this;
    }

    private CustomerInvoice requireInvoice(String orderNumber, CustomerInvoice.InvoiceType type) {
        return invoiceOfType(orderNumber, type).orElseThrow(() -> new IllegalStateException(
            "No " + type.dbValue() + " invoice for order " + orderNumber + " — cannot pay"));
    }

    /**
     * Cancel an order through the real {@code SalesOrderService}, then
     * {@link #settle()} — which drives the compensation Saga to quiescence:
     * sales emits {@code SalesOrderCancellationRequested}, inventory releases any
     * reservation and acks with {@code InventorySalesOrderCancellationApplied},
     * and the saga reaches {@code compensated} (emitting
     * {@code SalesOrderCompensated}). The order header folds to
     * {@link SalesOrder.Status#CANCELLED}.
     */
    public World cancel(String orderNumber, String reason) {
        sales.cancel(orderId(orderNumber), reason);
        settle();
        return this;
    }

    /**
     * Fire inventory's reorder-point detection for a product (the entry point the
     * shipment / adjustment services call after every on-hand decrement), then
     * {@link #settle()}. If on-hand is below the reorder point, inventory raises a
     * {@code ReplenishmentRequest} and the cascade dispatches it — a manufactured
     * product releases a stock work order and reserves its raw materials, parking
     * the work-order saga at {@code raw_materials_reserved}.
     */
    public World triggerReorderCheck(String productCode) {
        inventory.replenishmentDetection.checkAfterOnHandDecrement(
            InventoryTestKit.DEFAULT_WAREHOUSE_ID, product(productCode).productId());
        settle();
        return this;
    }

    /**
     * Drive the (real) completion of the stock work order replenishing a product:
     * resolves the work order from the product's dispatched replenishment request,
     * completes every operation through the real {@code WorkOrderOperationService}
     * (no forged completion event), then {@link #settle()} so inventory bumps the
     * finished-good stock and marks the replenishment fulfilled.
     */
    public World completeWorkOrder(String fgCode, BigDecimal actualMinutes) {
        ReplenishmentRequest request = replenishmentRequestFor(fgCode)
            .orElseThrow(() -> new IllegalStateException("No replenishment request for product " + fgCode));
        UUID workOrderId = request.dispatchedAggregateId();
        if (workOrderId == null) {
            throw new IllegalStateException(
                "Replenishment for " + fgCode + " has not been dispatched to a work order");
        }
        WorkOrder workOrder = manufacturing.workOrders.findById(WorkOrderId.of(workOrderId))
            .orElseThrow(() -> new IllegalStateException("Work order " + workOrderId + " not found"));
        for (WorkOrderOperation op : workOrder.operations()) {
            manufacturing.operationService.completeOperation(
                new CompleteOperationCommand(workOrderId, op.operationSequence(), actualMinutes));
        }
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
            for (Runnable ticker : sagaTickers) {
                ticker.run();
            }
            bus.drain();
        } while (totalOutboxRows() != previousRows);
    }

    private int totalOutboxRows() {
        int sum = 0;
        for (InMemoryOutboxPort outbox : outboxes) {
            sum += outbox.size();
        }
        return sum;
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

    /** Every customer payment finance has recorded. COD auto-records one at shipment; single-order scenarios. */
    public List<Payment> customerPayments() {
        return finance.payments.findAll();
    }

    /** Every journal entry finance has posted — the basis for GL-posting (Dr/Cr) assertions. */
    public List<JournalEntry> journalEntries() {
        return finance.journalEntries.all();
    }

    /** The COMMERCIAL invoice raised for an order (single-invoice {@code on_shipment} flow), if any. */
    public Optional<CustomerInvoice> commercialInvoice(String orderNumber) {
        return invoiceOfType(orderNumber, CustomerInvoice.InvoiceType.COMMERCIAL);
    }

    /** The DEPOSIT invoice raised at placement on a deposit order, if any. */
    public Optional<CustomerInvoice> depositInvoice(String orderNumber) {
        return invoiceOfType(orderNumber, CustomerInvoice.InvoiceType.DEPOSIT);
    }

    /** The BALANCE invoice raised at shipment on a deposit order, if any. */
    public Optional<CustomerInvoice> balanceInvoice(String orderNumber) {
        return invoiceOfType(orderNumber, CustomerInvoice.InvoiceType.BALANCE);
    }

    /** The invoice of a given type raised for an order, if finance has created one. */
    public Optional<CustomerInvoice> invoiceOfType(String orderNumber, CustomerInvoice.InvoiceType type) {
        UUID orderId = orderId(orderNumber);
        return finance.customerInvoices.findAll().stream()
            .filter(inv -> orderId.equals(inv.salesOrderHeaderId()))
            .filter(inv -> inv.invoiceType() == type)
            .findFirst();
    }

    /** Union of every kit's published + pending outbox event types — the {@code events_published(…)} basis. */
    public Set<String> publishedEventTypes() {
        Set<String> types = new LinkedHashSet<>();
        for (var kitOutbox : outboxes) {
            kitOutbox.all().stream().map(OutboxRow::getEventType).forEach(types::add);
        }
        return types;
    }

    /** The replenishment request inventory raised for a product, if any (reorder-point or shortage driven). */
    public Optional<ReplenishmentRequest> replenishmentRequestFor(String productCode) {
        UUID productId = product(productCode).productId();
        return inventory.replenishmentRequests.all().stream()
            .filter(r -> productId.equals(r.productId()))
            .findFirst();
    }

    /** The on-hand / reserved / available balance for a product at the default warehouse (ATP). */
    public StockBalanceView stockBalance(String productCode) {
        UUID productId = product(productCode).productId();
        return inventory.stockBalances
            .findBalance(InventoryTestKit.DEFAULT_WAREHOUSE_ID, productId)
            .orElseGet(() -> StockBalanceView.empty(InventoryTestKit.DEFAULT_WAREHOUSE_ID, productId));
    }

    /** How many times an event type was published across every wired kit's outbox (multiplicity, not just presence). */
    public long publishedEventCount(String eventType) {
        long count = 0;
        for (InMemoryOutboxPort outbox : outboxes) {
            count += outbox.all().stream().filter(r -> eventType.equals(r.getEventType())).count();
        }
        return count;
    }

    /** The engine UUID for a registered order number — for asserting peg / source-order links. */
    public UUID salesOrderId(String orderNumber) {
        return orderId(orderNumber);
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
