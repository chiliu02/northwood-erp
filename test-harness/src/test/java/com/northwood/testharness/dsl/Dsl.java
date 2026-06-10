package com.northwood.testharness.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.domain.Currencies;
import com.northwood.shared.domain.Money;
import com.northwood.testharness.dsl.Scenario.ActionStep;
import com.northwood.testharness.dsl.Scenario.AssertStep;
import com.northwood.testharness.dsl.Scenario.SeedStep;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The acceptance-DSL vocabulary (see {@code docs/dsl.md} §5). Static factory
 * methods that read as business English and resolve to real operations on the
 * {@link World}. A scenario file static-imports {@code Dsl.*}.
 *
 * <p>Nouns are business identifiers ({@code "FG-001"}, {@code "SO-9001"}); the
 * World resolves them to UUIDs. Verbs ({@code places_order}, {@code ships},
 * {@code pays}) name real kit operations. The DSL invents no vocabulary — the
 * warehouse codes, saga states, and {@code EVENT_TYPE}s a scenario asserts
 * against are the real constants, static-imported directly.
 *
 * <p>Slice 2: only the o2c happy path's vocabulary. Branch terms (deposit,
 * cancellation) land when a branch path is ported (slice 3).
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

        public SeedStep pricedAt(Money price) {
            return world -> world.seedProduct(productCode, productName, price.amount());
        }
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
    }

    /** Accumulates order lines, then places the order on {@code act}. */
    public static final class OrderPlacement implements ActionStep {
        private final String customerCode;
        private final String orderNumber;
        private final List<World.OrderLineSpec> lines = new ArrayList<>();

        private OrderPlacement(String customerCode, String orderNumber) {
            this.customerCode = customerCode;
            this.orderNumber = orderNumber;
        }

        public OrderPlacement line(String productCode, Qty quantity) {
            lines.add(new World.OrderLineSpec(productCode, quantity.amount()));
            return this;
        }

        @Override
        public void act(World world) {
            world.placeOrder(orderNumber, customerCode, lines);
        }
    }

    /** Resolves the order's commercial invoice and records the payment on {@code act}. */
    public static final class PaymentBuilder {
        private final Money amount;

        private PaymentBuilder(Money amount) {
            this.amount = amount;
        }

        public ActionStep against(String orderNumber) {
            return world -> world.pay("PAY-" + orderNumber, orderNumber, amount.amount());
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
    public static CommercialInvoiceAssertion a_commercial_invoice() {
        return new CommercialInvoiceAssertion();
    }

    public static final class CommercialInvoiceAssertion {
        private String orderNumber;

        private CommercialInvoiceAssertion() {
        }

        public CommercialInvoiceAssertion for_order(String orderNumber) {
            this.orderNumber = orderNumber;
            return this;
        }

        public AssertStep totalling(Money total) {
            return world -> {
                var invoice = world.commercialInvoice(orderNumber);
                assertThat(invoice).as("commercial invoice for order %s", orderNumber).isPresent();
                assertThat(invoice.orElseThrow().totalAmount())
                    .as("commercial invoice total for order %s", orderNumber)
                    .isEqualByComparingTo(total.amount());
            };
        }
    }

    /** The union of every kit's outbox contains each of the given {@code EVENT_TYPE}s. */
    public static AssertStep events_published(String... eventTypes) {
        return world -> assertThat(world.publishedEventTypes())
            .as("published event types")
            .contains(eventTypes);
    }
}
