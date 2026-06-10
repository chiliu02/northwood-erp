package com.northwood.testharness.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.JournalEntry;
import com.northwood.finance.domain.Payment;
import com.northwood.finance.domain.SupplierInvoice;
import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.domain.Currencies;
import com.northwood.shared.domain.Money;
import com.northwood.testharness.dsl.Scenario.ActionStep;
import com.northwood.testharness.dsl.Scenario.AssertStep;
import com.northwood.testharness.dsl.Scenario.SeedStep;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The acceptance-DSL vocabulary (see {@code docs/test-harness-dsl.md} §5). Static factory
 * methods that read as business English and resolve to real operations on the
 * {@link World}. A scenario file static-imports {@code Dsl.*}.
 *
 * <p>Nouns are business identifiers ({@code "FG-001"}, {@code "SO-9001"}); the
 * World resolves them to UUIDs. Verbs ({@code places_order}, {@code ships},
 * {@code pays}) name real kit operations. The DSL invents no vocabulary — the
 * warehouse codes, saga states, and {@code EVENT_TYPE}s a scenario asserts
 * against are the real constants, static-imported directly.
 *
 * <p>Slice 3: the o2c happy path, the deposit (part-payment) branch —
 * {@code with_deposit(percent(50))}, {@code pays(…).against_deposit_on/against_balance_on(…)},
 * {@code a_deposit_invoice()} / {@code a_balance_invoice()} — and the
 * cancellation/compensation branch — {@code places_order(…).without_settling()}
 * (the doc §6 escape hatch) + {@code customer(…).cancels(order).because(reason)}.
 */
public final class Dsl {

    private Dsl() {
    }

    // ============================================================
    // Value helpers — tiny, scale-correct
    // ============================================================

    /** {@code money(100)} → 100.00 in {@link Currencies#AUD}. */
    public static Money money(long amount) {
        return Money.of(new BigDecimal(amount).setScale(2), Currencies.AUD);
    }

    /** {@code qty(3)} → quantity 3. */
    public static Qty qty(long amount) {
        return new Qty(new BigDecimal(amount));
    }

    /** A quantity, kept as a thin VO so {@code qty(3)} reads right at call sites. */
    public record Qty(BigDecimal amount) {}

    /** {@code percent(50)} → a 50% up-front fraction for a deposit order. */
    public static Percent percent(long value) {
        return new Percent(new BigDecimal(value));
    }

    /** A percentage, kept as a thin VO so {@code with_deposit(percent(50))} reads right. */
    public record Percent(BigDecimal amount) {}

    // ============================================================
    // Given — seed the world (the guard)
    // ============================================================

    /** A known, active customer. */
    public static SeedStep a_customer(String customerCode, String customerName) {
        return world -> world.seedCustomer(customerCode, customerName);
    }

    /** A catalog product; price it with {@link ProductSeed#pricedAt}. */
    public static ProductSeed a_product(String productCode, String productName) {
        return new ProductSeed(productCode, productName);
    }

    public static final class ProductSeed {
        private final String productCode;
        private final String productName;

        private ProductSeed(String productCode, String productName) {
            this.productCode = productCode;
            this.productName = productName;
        }

        public PricedProduct pricedAt(Money price) {
            return new PricedProduct(productCode, productName, price);
        }
    }

    /**
     * A priced product. Usable directly as a {@code given} seed, or refined with
     * {@link #withPlanningFence} for the planning-time-fence path (REQ-SAL-037).
     */
    public static final class PricedProduct implements SeedStep {
        private final String productCode;
        private final String productName;
        private final Money price;

        private PricedProduct(String productCode, String productName, Money price) {
            this.productCode = productCode;
            this.productName = productName;
            this.price = price;
        }

        /** Give the product a planning time fence of {@code fenceDays}. */
        public SeedStep withPlanningFence(int fenceDays) {
            return world -> {
                world.seedProduct(productCode, productName, price.amount());
                world.seedProductFence(productCode, fenceDays);
            };
        }

        /**
         * Make the (sold) product make-to-order (REQ-INV-093): manufactured +
         * order-pegged, so its sales-order line raises dedicated supply routed to
         * manufacturing. Pair with {@code a_bom(...)} + {@code a_routing(...)}.
         */
        public SeedStep manufacturedToOrder() {
            return world -> {
                world.seedProduct(productCode, productName, price.amount());
                world.markManufacturedToOrder(productCode);
            };
        }

        /**
         * Make the (sold) product buy-to-order (REQ-INV-093): purchased +
         * order-pegged with the given default-supplier price, so its sales-order
         * line raises dedicated supply routed to purchasing.
         */
        public SeedStep purchasedToOrder(Money supplierPrice) {
            return world -> {
                world.seedProduct(productCode, productName, price.amount());
                world.markPurchasedToOrder(productCode, supplierPrice.amount());
            };
        }

        @Override
        public void seed(World world) {
            world.seedProduct(productCode, productName, price.amount());
        }
    }

    /** Set the world clock (UTC start-of-day) the fulfilment worker reads for the planning-time fence. */
    public static SeedStep clock_at(LocalDate date) {
        return world -> world.setClockAt(date);
    }

    /** On-hand stock for a product; place it with {@link StockSeed#at}. */
    public static StockSeed stock_on_hand(String productCode, Qty quantity) {
        return new StockSeed(productCode, quantity);
    }

    public static final class StockSeed {
        private final String productCode;
        private final Qty quantity;

        private StockSeed(String productCode, Qty quantity) {
            this.productCode = productCode;
            this.quantity = quantity;
        }

        /**
         * Place the stock at a warehouse. The in-memory inventory kit models a
         * single warehouse ({@link com.northwood.inventory.domain.WarehouseCodes#MAIN}),
         * so the code is named for readability; multi-warehouse seeding is a
         * later concern. Passing any other code is a deliberate signal the
         * harness doesn't yet model it.
         */
        public SeedStep at(String warehouseCode) {
            return world -> world.seedStock(productCode, quantity.amount());
        }
    }

    // ── manufacturing seeds (make-to-stock products, BOMs, routings, policy) ──

    /** A manufactured (make-to-stock) product — replenished by a work order, not sold. */
    public static SeedStep a_manufactured_product(String productCode, String productName) {
        return world -> world.seedManufacturedProduct(productCode, productName);
    }

    /** A raw-material product (a BOM component). */
    public static SeedStep a_raw_material(String productCode, String productName) {
        return world -> world.seedRawMaterial(productCode, productName);
    }

    /** An active BOM for a manufactured product; add lines with {@link BomSeed#withRawLine} / {@link BomSeed#withSubAssembly}. */
    public static BomSeed a_bom(String fgCode) {
        return new BomSeed(fgCode);
    }

    public static final class BomSeed implements SeedStep {
        private final String fgCode;
        private final List<World.BomLineSpec> lines = new ArrayList<>();

        private BomSeed(String fgCode) {
            this.fgCode = fgCode;
        }

        public BomSeed withRawLine(String rawCode, Qty qtyPerUnit) {
            lines.add(new World.BomLineSpec(rawCode, qtyPerUnit.amount(), false));
            return this;
        }

        public BomSeed withSubAssembly(String subAssemblyCode, Qty qtyPerUnit) {
            lines.add(new World.BomLineSpec(subAssemblyCode, qtyPerUnit.amount(), true));
            return this;
        }

        @Override
        public void seed(World world) {
            world.seedBom(fgCode, lines);
        }
    }

    /** A single-operation active routing for a manufactured product. */
    public static RoutingSeed a_routing(String fgCode) {
        return new RoutingSeed(fgCode);
    }

    public static final class RoutingSeed {
        private final String fgCode;

        private RoutingSeed(String fgCode) {
            this.fgCode = fgCode;
        }

        public SeedStep singleOp() {
            return world -> world.seedSingleOpRouting(fgCode);
        }
    }

    /** A reorder policy for a product; set the point then the {@link ReorderPolicySeed#quantity}. */
    public static ReorderPolicySeed reorder_policy(String productCode) {
        return new ReorderPolicySeed(productCode);
    }

    public static final class ReorderPolicySeed {
        private final String productCode;
        private Qty point;

        private ReorderPolicySeed(String productCode) {
            this.productCode = productCode;
        }

        public ReorderPolicySeed point(Qty point) {
            this.point = point;
            return this;
        }

        public SeedStep quantity(Qty quantity) {
            return world -> world.seedReorderPolicy(productCode, point.amount(), quantity.amount());
        }
    }

    /** A purchasable product (a bought raw material); give it a supplier price with {@link PurchasableProductSeed#suppliedAt}. */
    public static PurchasableProductSeed a_purchasable_product(String productCode, String productName) {
        return new PurchasableProductSeed(productCode, productName);
    }

    public static final class PurchasableProductSeed implements SeedStep {
        private final String productCode;
        private final String productName;

        private PurchasableProductSeed(String productCode, String productName) {
            this.productCode = productCode;
            this.productName = productName;
        }

        /** Publish the default supplier's unit price for the product. */
        public SeedStep suppliedAt(Money unitPrice) {
            return world -> {
                world.seedPurchasableProduct(productCode, productName);
                world.seedSupplierPrice(productCode, unitPrice.amount());
            };
        }

        @Override
        public void seed(World world) {
            world.seedPurchasableProduct(productCode, productName);
        }
    }

    // ============================================================
    // When — drive a domain action (the trigger)
    // ============================================================

    /** A customer who can place an order or settle an invoice. */
    public static CustomerActions customer(String customerCode) {
        return new CustomerActions(customerCode);
    }

    public static final class CustomerActions {
        private final String customerCode;

        private CustomerActions(String customerCode) {
            this.customerCode = customerCode;
        }

        public OrderPlacement places_order(String orderNumber) {
            return new OrderPlacement(customerCode, orderNumber);
        }

        public PaymentBuilder pays(Money amount) {
            return new PaymentBuilder(amount);
        }

        /** Cancel one of the customer's orders; state the {@code .because(reason)}. */
        public Cancellation cancels(String orderNumber) {
            return new Cancellation(orderNumber);
        }
    }

    /** Cancels an order with a reason on {@code act}. */
    public static final class Cancellation {
        private final String orderNumber;

        private Cancellation(String orderNumber) {
            this.orderNumber = orderNumber;
        }

        public ActionStep because(String reason) {
            return world -> world.cancel(orderNumber, reason);
        }
    }

    /** Accumulates order lines, then places the order on {@code act}. */
    public static final class OrderPlacement implements ActionStep {
        private final String customerCode;
        private final String orderNumber;
        private final List<World.OrderLineSpec> lines = new ArrayList<>();
        private Percent deposit;
        private boolean cod;
        private boolean settle = true;
        private LocalDate needBy;

        private OrderPlacement(String customerCode, String orderNumber) {
            this.customerCode = customerCode;
            this.orderNumber = orderNumber;
        }

        public OrderPlacement line(String productCode, Qty quantity) {
            lines.add(new World.OrderLineSpec(productCode, quantity.amount()));
            return this;
        }

        /**
         * Set the requested-delivery (need-by) date. Inert unless the product
         * carries a planning time fence ({@code a_product(...).pricedAt(...)
         * .withPlanningFence(days)}), in which case a far-future date parks the
         * order at {@code awaiting_release} (REQ-SAL-013/037).
         */
        public OrderPlacement needBy(LocalDate date) {
            this.needBy = date;
            return this;
        }

        /**
         * Make this a deposit (part-payment) order: {@code deposit} is invoiced
         * + paid up front, the balance invoiced at shipment. Omit for standard
         * {@code on_shipment} terms.
         */
        public OrderPlacement with_deposit(Percent deposit) {
            this.deposit = deposit;
            return this;
        }

        /**
         * Cash-on-delivery terms: at shipment finance auto-creates the invoice
         * and auto-records a full cash payment — there is no operator payment
         * step, so the scenario has no {@code pays(...)} {@code when}.
         */
        public OrderPlacement cash_on_delivery() {
            this.cod = true;
            return this;
        }

        /**
         * The {@code settle()} escape hatch (doc §6): place the order but leave
         * the world un-settled, so a following action observes / acts on the
         * not-yet-processed order (e.g. cancel before the worker reserves stock).
         */
        public OrderPlacement without_settling() {
            this.settle = false;
            return this;
        }

        @Override
        public void act(World world) {
            if (cod) {
                world.placeCodOrder(orderNumber, customerCode, lines);
            } else if (deposit != null) {
                world.placeDepositOrder(orderNumber, customerCode, deposit.amount(), lines);
            } else if (settle) {
                if (needBy != null) {
                    world.placeOrder(orderNumber, customerCode, needBy, lines);
                } else {
                    world.placeOrder(orderNumber, customerCode, lines);
                }
            } else {
                world.placeOrderWithoutSettling(orderNumber, customerCode, lines);
            }
        }
    }

    /** Resolves an order's invoice (commercial / deposit / balance) and records the payment on {@code act}. */
    public static final class PaymentBuilder {
        private final Money amount;

        private PaymentBuilder(Money amount) {
            this.amount = amount;
        }

        /** Pay the order's COMMERCIAL invoice (the single-invoice {@code on_shipment} flow). */
        public ActionStep against(String orderNumber) {
            return world -> world.pay("PAY-" + orderNumber, orderNumber, amount.amount());
        }

        /** Pay the up-front DEPOSIT invoice on a deposit order. */
        public ActionStep against_deposit_on(String orderNumber) {
            return world -> world.payDeposit("PAY-DEP-" + orderNumber, orderNumber, amount.amount());
        }

        /** Pay the BALANCE invoice raised at shipment on a deposit order. */
        public ActionStep against_balance_on(String orderNumber) {
            return world -> world.payBalance("PAY-BAL-" + orderNumber, orderNumber, amount.amount());
        }
    }

    /** A warehouse that can ship an order. */
    public static WarehouseActions warehouse(String warehouseCode) {
        return new WarehouseActions(warehouseCode);
    }

    public static final class WarehouseActions {
        private final String warehouseCode;

        private WarehouseActions(String warehouseCode) {
            this.warehouseCode = warehouseCode;
        }

        public Shipment ships(String orderNumber) {
            return new Shipment(orderNumber);
        }

        /** Receive goods (for real) against the purchase order raised from a requisition. */
        public ActionStep receives_goods_for(String requisitionNumber) {
            return world -> world.receiveGoodsForRequisition(requisitionNumber, "GR-" + requisitionNumber);
        }
    }

    /**
     * Accumulates shipment lines as {@code .line(code, qty).at_unit_cost(cost)}
     * pairs, then posts the shipment on {@code act}.
     */
    public static final class Shipment implements ActionStep {
        private final String orderNumber;
        private final List<World.ShipLineSpec> lines = new ArrayList<>();
        private String pendingProductCode;
        private Qty pendingQuantity;

        private Shipment(String orderNumber) {
            this.orderNumber = orderNumber;
        }

        public Shipment line(String productCode, Qty quantity) {
            this.pendingProductCode = productCode;
            this.pendingQuantity = quantity;
            return this;
        }

        public Shipment at_unit_cost(Money unitCost) {
            if (pendingProductCode == null) {
                throw new IllegalStateException("at_unit_cost() must follow a line(...) call");
            }
            lines.add(new World.ShipLineSpec(pendingProductCode, pendingQuantity.amount(), unitCost.amount()));
            pendingProductCode = null;
            pendingQuantity = null;
            return this;
        }

        @Override
        public void act(World world) {
            world.ship("SHIP-" + orderNumber, orderNumber, lines);
        }
    }

    /** Inventory's reorder-point monitor finds this product below its reorder point and raises replenishment. */
    public static ActionStep reorder_point_breached(String productCode) {
        return world -> world.triggerReorderCheck(productCode);
    }

    /** Goods are received (for real) against the purchase order replenishing a product, fulfilling the request. */
    public static ActionStep goods_received_for(String productCode) {
        return world -> world.receiveGoodsForReplenishment(productCode, "GR-" + productCode);
    }

    // ── procure-to-pay actors ──

    /** A buyer who can raise a requisition or approve a purchase order. */
    public static BuyerActions buyer() {
        return new BuyerActions();
    }

    public static final class BuyerActions {
        private BuyerActions() {
        }

        public RequisitionBuilder raises_requisition(String requisitionNumber) {
            return new RequisitionBuilder(requisitionNumber);
        }

        /** Approve the purchase order auto-created from the named requisition. */
        public ActionStep approves_the_po_for(String requisitionNumber) {
            return world -> world.approvePurchaseOrder(requisitionNumber, "approved");
        }
    }

    /** Accumulates the requisition line, then raises the manual requisition on {@code act}. */
    public static final class RequisitionBuilder implements ActionStep {
        private final String requisitionNumber;
        private String productCode;
        private Qty quantity;

        private RequisitionBuilder(String requisitionNumber) {
            this.requisitionNumber = requisitionNumber;
        }

        public RequisitionBuilder line(String productCode, Qty quantity) {
            this.productCode = productCode;
            this.quantity = quantity;
            return this;
        }

        @Override
        public void act(World world) {
            world.raiseManualRequisition(requisitionNumber, productCode, quantity.amount());
        }
    }

    /** The supplier, who invoices against a PO and receives payment. */
    public static SupplierActions the_supplier() {
        return new SupplierActions();
    }

    public static final class SupplierActions {
        private SupplierActions() {
        }

        public SupplierInvoiceBuilder invoices(String internalInvoiceNumber) {
            return new SupplierInvoiceBuilder(internalInvoiceNumber);
        }

        public SupplierPaymentBuilder is_paid(String paymentNumber) {
            return new SupplierPaymentBuilder(paymentNumber);
        }
    }

    public static final class SupplierInvoiceBuilder {
        private final String internalInvoiceNumber;
        private String requisitionNumber;

        private SupplierInvoiceBuilder(String internalInvoiceNumber) {
            this.internalInvoiceNumber = internalInvoiceNumber;
        }

        public SupplierInvoiceBuilder for_requisition(String requisitionNumber) {
            this.requisitionNumber = requisitionNumber;
            return this;
        }

        /** Invoice at a unit price; &gt; 2% over the PO price forces a three-way-match failure. */
        public ActionStep at_unit_price(Money unitPrice) {
            return world -> world.recordSupplierInvoice(internalInvoiceNumber, requisitionNumber, unitPrice.amount());
        }
    }

    public static final class SupplierPaymentBuilder {
        private final String paymentNumber;

        private SupplierPaymentBuilder(String paymentNumber) {
            this.paymentNumber = paymentNumber;
        }

        public ActionStep of(Money amount) {
            return world -> world.paySupplier(paymentNumber, amount.amount());
        }
    }

    /** An AP reviewer who can reject a parked supplier invoice. */
    public static ReviewerActions a_reviewer() {
        return new ReviewerActions();
    }

    public static final class ReviewerActions {
        private ReviewerActions() {
        }

        public RejectionBuilder rejects_the_supplier_invoice() {
            return new RejectionBuilder();
        }
    }

    public static final class RejectionBuilder {
        private RejectionBuilder() {
        }

        public ActionStep because(String reason) {
            return world -> world.rejectSupplierInvoice("ap-reviewer", reason);
        }
    }

    /** The stock work order replenishing a product; drive it through completion. */
    public static WorkOrderAction work_order_for(String fgCode) {
        return new WorkOrderAction(fgCode);
    }

    public static final class WorkOrderAction {
        private final String fgCode;

        private WorkOrderAction(String fgCode) {
            this.fgCode = fgCode;
        }

        /** Complete every operation through the real operation service (no forged completion event). */
        public ActionStep completes_manufacturing() {
            return world -> world.completeWorkOrder(fgCode, new BigDecimal("45"));
        }
    }

    // ============================================================
    // Then — assert the outcome
    // ============================================================

    /** Assertions about an order's saga state / header status / completion. */
    public static OrderAssertion order(String orderNumber) {
        return new OrderAssertion(orderNumber);
    }

    public static final class OrderAssertion {
        private final String orderNumber;

        private OrderAssertion(String orderNumber) {
            this.orderNumber = orderNumber;
        }

        /** The fulfilment saga reaches the given state (a {@code SalesOrderFulfilmentSaga.*} constant). */
        public AssertStep reaches(String sagaState) {
            return world -> assertThat(world.sagaState(orderNumber))
                .as("saga state for order %s", orderNumber)
                .isEqualTo(sagaState);
        }

        /** The order header's status fold equals the given status. */
        public AssertStep has_status(SalesOrder.Status status) {
            return world -> assertThat(world.orderStatus(orderNumber))
                .as("header status for order %s", orderNumber)
                .contains(status);
        }

        /** The order is fully done: saga {@code completed} and header status {@code COMPLETED}. */
        public AssertStep is_completed() {
            return world -> {
                assertThat(world.sagaState(orderNumber))
                    .as("saga state for order %s", orderNumber)
                    .isEqualTo(SalesOrderFulfilmentSaga.COMPLETED);
                assertThat(world.orderStatus(orderNumber))
                    .as("header status for order %s", orderNumber)
                    .contains(SalesOrder.Status.COMPLETED);
            };
        }
    }

    /** Assertion that a COMMERCIAL invoice exists for an order with a given total. */
    public static InvoiceAssertion a_commercial_invoice() {
        return new InvoiceAssertion(CustomerInvoice.InvoiceType.COMMERCIAL);
    }

    /** Assertion that a DEPOSIT invoice (up-front part-payment) exists for an order with a given total. */
    public static InvoiceAssertion a_deposit_invoice() {
        return new InvoiceAssertion(CustomerInvoice.InvoiceType.DEPOSIT);
    }

    /** Assertion that a BALANCE invoice (the remainder, raised at shipment) exists for an order with a given total. */
    public static InvoiceAssertion a_balance_invoice() {
        return new InvoiceAssertion(CustomerInvoice.InvoiceType.BALANCE);
    }

    public static final class InvoiceAssertion {
        private final CustomerInvoice.InvoiceType type;
        private String orderNumber;

        private InvoiceAssertion(CustomerInvoice.InvoiceType type) {
            this.type = type;
        }

        public InvoiceAssertion for_order(String orderNumber) {
            this.orderNumber = orderNumber;
            return this;
        }

        public AssertStep totalling(Money total) {
            return world -> {
                var invoice = world.invoiceOfType(orderNumber, type);
                assertThat(invoice).as("%s invoice for order %s", type.dbValue(), orderNumber).isPresent();
                assertThat(invoice.orElseThrow().totalAmount())
                    .as("%s invoice total for order %s", type.dbValue(), orderNumber)
                    .isEqualByComparingTo(total.amount());
            };
        }
    }

    /** Assertion that finance recorded a customer payment by a given method (COD auto-records a cash payment). */
    public static CustomerPaymentAssertion a_customer_payment() {
        return new CustomerPaymentAssertion();
    }

    public static final class CustomerPaymentAssertion {
        private Payment.Method method;

        private CustomerPaymentAssertion() {
        }

        public CustomerPaymentAssertion byMethod(Payment.Method method) {
            this.method = method;
            return this;
        }

        public AssertStep wasRecorded() {
            return world -> assertThat(world.customerPayments())
                .as("a customer payment by method %s", method)
                .anyMatch(p -> p.paymentMethod() == method);
        }
    }

    /**
     * Assertion that finance posted a journal of a given source-document type
     * with a specific debit and credit line — the GL-posting (REQ-FIN-0xx) check.
     */
    public static JournalAssertion a_journal() {
        return new JournalAssertion();
    }

    public static final class JournalAssertion {
        private JournalEntry.SourceDocumentType type;
        private String debitAccount;
        private Money debitAmount;
        private String creditAccount;
        private Money creditAmount;

        private JournalAssertion() {
        }

        public JournalAssertion of_type(JournalEntry.SourceDocumentType type) {
            this.type = type;
            return this;
        }

        public JournalAssertion debiting(String account, Money amount) {
            this.debitAccount = account;
            this.debitAmount = amount;
            return this;
        }

        public JournalAssertion crediting(String account, Money amount) {
            this.creditAccount = account;
            this.creditAmount = amount;
            return this;
        }

        public AssertStep posted() {
            return world -> {
                var entry = world.journalEntries().stream()
                    .filter(e -> e.sourceDocumentType() == type)
                    .findFirst();
                assertThat(entry).as("a %s journal", type).isPresent();
                var lines = entry.orElseThrow().lines();
                assertThat(lines.stream()
                        .filter(l -> debitAccount.equals(l.accountCode()))
                        .map(l -> l.debitAmount())
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .as("%s debit to %s", type, debitAccount)
                    .isEqualByComparingTo(debitAmount.amount());
                assertThat(lines.stream()
                        .filter(l -> creditAccount.equals(l.accountCode()))
                        .map(l -> l.creditAmount())
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .as("%s credit to %s", type, creditAccount)
                    .isEqualByComparingTo(creditAmount.amount());
            };
        }
    }

    /** A GL account; assert it nets to zero across every posted journal (e.g. 2110 after deposit + refund). */
    public static GlAccountAssertion gl_account(String accountCode) {
        return new GlAccountAssertion(accountCode);
    }

    public static final class GlAccountAssertion {
        private final String accountCode;

        private GlAccountAssertion(String accountCode) {
            this.accountCode = accountCode;
        }

        public AssertStep netsToZero() {
            return world -> assertThat(world.journalEntries().stream()
                    .flatMap(e -> e.lines().stream())
                    .filter(l -> accountCode.equals(l.accountCode()))
                    .map(l -> l.debitAmount().subtract(l.creditAmount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("GL account %s nets to zero", accountCode)
                .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    /**
     * Assertion about the replenishment request inventory raised for a product —
     * its routing, reason, quantity, and lifecycle status (REQ-INV-080/081).
     */
    public static ReplenishmentAssertion a_replenishment_request(String productCode) {
        return new ReplenishmentAssertion(productCode);
    }

    public static final class ReplenishmentAssertion {
        private final String productCode;
        private ReplenishmentRequest.TargetService target;
        private ReplenishmentRequest.Reason reason;
        private BigDecimal quantity;
        private String peggedOrder;

        private ReplenishmentAssertion(String productCode) {
            this.productCode = productCode;
        }

        public ReplenishmentAssertion routedTo(ReplenishmentRequest.TargetService target) {
            this.target = target;
            return this;
        }

        public ReplenishmentAssertion because(ReplenishmentRequest.Reason reason) {
            this.reason = reason;
            return this;
        }

        public ReplenishmentAssertion ofQuantity(Qty quantity) {
            this.quantity = quantity.amount();
            return this;
        }

        /** Assert the request is pegged to the given order (source sales order header). */
        public ReplenishmentAssertion forOrder(String orderNumber) {
            this.peggedOrder = orderNumber;
            return this;
        }

        public AssertStep reaches(ReplenishmentRequest.Status status) {
            return world -> {
                var request = world.replenishmentRequestFor(productCode);
                assertThat(request).as("replenishment request for %s", productCode).isPresent();
                ReplenishmentRequest r = request.orElseThrow();
                if (target != null) {
                    assertThat(r.targetService()).as("target service for %s", productCode).isEqualTo(target);
                }
                if (reason != null) {
                    assertThat(r.reason()).as("reason for %s", productCode).isEqualTo(reason);
                }
                if (quantity != null) {
                    assertThat(r.requestedQuantity()).as("requested quantity for %s", productCode)
                        .isEqualByComparingTo(quantity);
                }
                if (peggedOrder != null) {
                    assertThat(r.sourceSalesOrderHeaderId()).as("pegged order for %s", productCode)
                        .isEqualTo(world.salesOrderId(peggedOrder));
                }
                assertThat(r.status()).as("replenishment status for %s", productCode).isEqualTo(status);
            };
        }
    }

    /** Assertion about the work order producing a product (released? make-to-stock?). */
    public static WorkOrderAssertion a_work_order_for(String productCode) {
        return new WorkOrderAssertion(productCode);
    }

    public static final class WorkOrderAssertion {
        private final String productCode;

        private WorkOrderAssertion(String productCode) {
            this.productCode = productCode;
        }

        /** A work order producing this product was released. */
        public AssertStep wasCreated() {
            return world -> assertThat(world.workOrderForProduct(productCode))
                .as("a work order for %s", productCode).isPresent();
        }

        /** A work order producing this product exists and is make-to-stock (no sales-order peg). */
        public AssertStep isMakeToStock() {
            return world -> {
                var saga = world.workOrderSagaForProduct(productCode);
                assertThat(saga).as("a work order saga for %s", productCode).isPresent();
                assertThat(saga.orElseThrow().salesOrderHeaderId())
                    .as("make-to-stock (no sales order) for %s", productCode).isNull();
            };
        }
    }

    /** Assertion on a product's on-hand / reserved / available balance (ATP — pegged stock shows 0 available). */
    public static StockBalanceAssertion a_stock_balance(String productCode) {
        return new StockBalanceAssertion(productCode);
    }

    public static final class StockBalanceAssertion {
        private final String productCode;

        private StockBalanceAssertion(String productCode) {
            this.productCode = productCode;
        }

        public AssertStep shows(Qty onHand, Qty reserved, Qty available) {
            return world -> {
                var balance = world.stockBalance(productCode);
                assertThat(balance.onHand()).as("on-hand for %s", productCode).isEqualByComparingTo(onHand.amount());
                assertThat(balance.reserved()).as("reserved for %s", productCode).isEqualByComparingTo(reserved.amount());
                assertThat(balance.available()).as("available for %s", productCode).isEqualByComparingTo(available.amount());
            };
        }
    }

    /** The given event type was published exactly {@code times} across all kits (multiplicity, e.g. no-retry proof). */
    public static AssertStep events_published_count(String eventType, long times) {
        return world -> assertThat(world.publishedEventCount(eventType))
            .as("times %s was published", eventType)
            .isEqualTo(times);
    }

    /** Assertion about the purchase-to-pay saga / payment state of a requisition's purchase order. */
    public static PurchaseOrderAssertion a_purchase_order_for(String requisitionNumber) {
        return new PurchaseOrderAssertion(requisitionNumber);
    }

    public static final class PurchaseOrderAssertion {
        private final String requisitionNumber;

        private PurchaseOrderAssertion(String requisitionNumber) {
            this.requisitionNumber = requisitionNumber;
        }

        /** The purchase-to-pay saga reaches the given state (a {@code PurchaseToPaySaga.*} constant). */
        public AssertStep reaches(String sagaState) {
            return world -> assertThat(world.purchaseToPaySaga(requisitionNumber).state())
                .as("purchase-to-pay saga for %s", requisitionNumber).isEqualTo(sagaState);
        }

        public AssertStep is_fully_paid() {
            return world -> assertThat(world.isPurchaseOrderFullyPaid(requisitionNumber))
                .as("purchase order for %s fully paid", requisitionNumber).isTrue();
        }
    }

    /** Assertion about the supplier invoice on file (single-invoice p2p scenarios). */
    public static SupplierInvoiceAssertion a_supplier_invoice() {
        return new SupplierInvoiceAssertion();
    }

    public static final class SupplierInvoiceAssertion {
        private SupplierInvoiceAssertion() {
        }

        public AssertStep reaches(SupplierInvoice.Status status) {
            return world -> assertThat(world.supplierInvoice().status())
                .as("supplier invoice status").isEqualTo(status);
        }
    }

    /** The union of every kit's outbox contains each of the given {@code EVENT_TYPE}s. */
    public static AssertStep events_published(String... eventTypes) {
        return world -> assertThat(world.publishedEventTypes())
            .as("published event types")
            .contains(eventTypes);
    }
}
