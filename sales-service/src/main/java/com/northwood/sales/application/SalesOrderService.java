package com.northwood.sales.application;

import com.northwood.sales.application.CustomerLookup.CustomerSummary;
import com.northwood.sales.application.ProductCardLookup.CatalogPrice;
import com.northwood.sales.application.dto.CancelOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.application.dto.SalesOrderView;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrder.ShippedLineInput;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderLine;
import com.northwood.sales.domain.SalesOrderRepository;
import com.northwood.shared.domain.LineNumbering;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for sales orders. {@link #placeOrder(PlaceOrderCommand)}
 * is the entry-point use case for the slice: it persists the order, emits
 * {@code sales.SalesOrderPlaced} via the outbox, and inserts a saga row in
 * {@code 'started'} so the worker can pick it up.
 *
 * <p>Per-line pricing semantics:
 * <ul>
 *   <li>{@code unitPrice == null} → auto-fill from {@code sales.product_card}.
 *       If the SKU has no projection row, throw {@link UnknownPriceException}
 *       (400). The caller may always supply an explicit override to bypass.</li>
 *   <li>{@code unitPrice != null} → accept as-is (negotiated/override price);
 *       the catalog row is still consulted to verify currency.</li>
 *   <li>Currency: when a projection row exists, the order's
 *       {@code currencyCode} must match the catalog's {@code currency_code} —
 *       otherwise throw {@link CurrencyMismatchException} (400). Cross-currency
 *       orders need an explicit conversion (out of scope this slice).</li>
 * </ul>
 */
@Service
public class SalesOrderService {

    public static class CustomerNotFoundException extends RuntimeException {
        public CustomerNotFoundException(String code) {
            super("Customer not found: " + code);
        }
    }

    /**
     * Thrown when {@link #placeOrder(PlaceOrderCommand)} resolves the customer
     * but its {@code status != 'active'} (i.e. {@code 'inactive'} or
     * {@code 'blocked'}). Mapped to HTTP 409 by the controller — the customer
     * exists, the order request is malformed against current state.
     */
    public static class CustomerInactiveException extends RuntimeException {
        public CustomerInactiveException(String code, Customer.Status status) {
            super("Customer " + code + " is " + status.dbValue() + "; cannot accept new orders");
        }
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(UUID id) {
            super("Sales order not found: " + id);
        }
    }

    public static class SagaNotFoundException extends RuntimeException {
        public SagaNotFoundException(UUID salesOrderHeaderId) {
            super("No fulfilment saga for sales order " + salesOrderHeaderId);
        }
    }

    /**
     * Application-layer wrapper around the domain
     * {@link SalesOrder.OrderNotCancellableException}. Controllers catch this
     * (HTTP 409) instead of reaching into {@code domain/} for the exception
     * type — keeps the api → application → domain layering clean.
     */
    public static class OrderNotCancellableException extends RuntimeException {
        public OrderNotCancellableException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    public static class UnknownPriceException extends RuntimeException {
        public UnknownPriceException(String sku) {
            super("No catalog price for sku=" + sku + "; provide unitPrice on the line or wait for the projection to catch up");
        }
    }

    public static class CurrencyMismatchException extends RuntimeException {
        public CurrencyMismatchException(String sku, String orderCurrency, String catalogCurrency) {
            super("Order currency " + orderCurrency + " does not match catalog currency "
                + catalogCurrency + " for sku=" + sku);
        }
    }

    public static class ProductDiscontinuedException extends RuntimeException {
        public ProductDiscontinuedException(String sku, java.time.Instant discontinuedAt) {
            super("Product sku=" + sku + " was discontinued at " + discontinuedAt
                + "; cannot accept new order lines for it");
        }
    }

    private final SalesOrderRepository salesOrders;
    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final CustomerLookup customers;
    private final ProductCardLookup productCards;

    public SalesOrderService(
        SalesOrderRepository salesOrders,
        SalesOrderFulfilmentSagaManager sagaManager,
        CustomerLookup customers,
        ProductCardLookup productCards
    ) {
        this.salesOrders = salesOrders;
        this.sagaManager = sagaManager;
        this.customers = customers;
        this.productCards = productCards;
    }

    /**
     * Cancel a sales order and kick off saga compensation. The header is
     * flipped to {@code 'cancelled'} (with {@code cancelled_at = now()}) and a
     * {@code sales.SalesOrderCancellationRequested} event is written to the
     * outbox. The fulfilment saga is moved to {@code 'compensating'}; the
     * downstream services (inventory, manufacturing) ack via their own
     * {@code InventorySalesOrderCancellationApplied} / {@code ManufacturingSalesOrderCancellationApplied}
     * events, after which the saga advances to {@code 'compensated'}.
     *
     * <p>Cancellable up to (and including) {@code ready_to_ship}; once
     * {@code goods_shipped} or beyond, the credit-note / return-goods flow
     * applies (out of scope — dev-todo §4.2).
     *
     * @throws OrderNotFoundException if no order with this id exists.
     * @throws OrderNotCancellableException if header status is past cancellation point.
     * @throws SagaNotFoundException if the fulfilment saga row is missing
     *         (shouldn't happen in practice; defensive — placement always
     *         inserts a saga row).
     */
    @Transactional
    public void cancel(CancelOrderCommand command) {
        SalesOrder order = salesOrders.findById(SalesOrderId.of(command.salesOrderHeaderId()))
            .orElseThrow(() -> new OrderNotFoundException(command.salesOrderHeaderId()));

        try {
            order.cancel(command.reason());
        } catch (SalesOrder.OrderNotCancellableException e) {
            throw new OrderNotCancellableException(e);
        }
        salesOrders.save(order);

        try {
            sagaManager.requestCompensation(command.salesOrderHeaderId());
        } catch (SalesOrderFulfilmentSagaManager.SagaNotFoundException e) {
            throw new SagaNotFoundException(command.salesOrderHeaderId());
        }
    }

    @Transactional(readOnly = true)
    public Optional<SalesOrderView> findById(UUID salesOrderHeaderId) {
        return salesOrders.findById(SalesOrderId.of(salesOrderHeaderId)).map(SalesOrderView::from);
    }

    @Transactional
    public SalesOrderView placeOrder(PlaceOrderCommand command) {
        CustomerSummary customer = customers.findByCode(command.customerCode())
            .orElseThrow(() -> new CustomerNotFoundException(command.customerCode()));
        if (customer.status() != Customer.Status.ACTIVE) {
            throw new CustomerInactiveException(command.customerCode(), customer.status());
        }

        List<SalesOrderLine> lines = new ArrayList<>();
        int lineNumber = LineNumbering.START;
        for (OrderLine req : command.lines()) {
            BigDecimal resolvedUnitPrice = resolveUnitPrice(req, command.currencyCode());
            lines.add(new SalesOrderLine(
                UUID.randomUUID(),
                lineNumber,
                req.productId(),
                req.productSku(),
                req.productName(),
                req.orderedQuantity(),
                resolvedUnitPrice,
                req.taxRate() == null ? BigDecimal.ZERO : req.taxRate(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                SalesOrder.LineStatus.OPEN
            ));
            lineNumber += LineNumbering.STEP;
        }

        SalesOrder order = SalesOrder.place(
            command.orderNumber(),
            customer.customerId(),
            customer.customerCode(),
            customer.customerName(),
            command.requestedDeliveryDate(),
            command.currencyCode(),
            BigDecimal.ONE,
            lines
        );

        salesOrders.save(order);

        sagaManager.insertStarted(order.id().value(), "{}");

        return SalesOrderView.from(order);
    }

    /**
     * Loads the {@link SalesOrder}, calls {@code recordShipped(...)} to emit
     * {@code SalesOrderShipped}, and saves — the repository drains the pending
     * event onto the outbox in the same transaction. Called from the inbox
     * handler that consumes inventory's {@code ShipmentPosted}.
     */
    @Transactional
    public void recordShipped(
        UUID salesOrderHeaderId,
        UUID shipmentHeaderId,
        String shipmentNumber,
        LocalDate shipmentDate,
        List<ShippedLineInput> shippedLines
    ) {
        SalesOrder order = salesOrders.findById(SalesOrderId.of(salesOrderHeaderId))
            .orElseThrow(() -> new IllegalStateException(
                "No sales_order_header for sales_order_header_id=" + salesOrderHeaderId));
        order.recordShipped(shipmentHeaderId, shipmentNumber, shipmentDate, new ArrayList<>(shippedLines));
        salesOrders.save(order);
    }

    private BigDecimal resolveUnitPrice(OrderLine req, String orderCurrency) {
        Optional<CatalogPrice> catalog = productCards.findByProductId(req.productId());

        // Discontinued check fires regardless of whether caller passed a
        // unitPrice — a manual override doesn't override product-service's
        // retirement of the SKU.
        catalog.ifPresent(cp -> {
            if (cp.discontinuedAt() != null) {
                throw new ProductDiscontinuedException(req.productSku(), cp.discontinuedAt());
            }
        });

        // The row is seeded on ProductCreated with NULL price+currency, so
        // catalog.isPresent() now means "we know about the product" — not
        // "the product is sellable". Sellability = salesPrice IS NOT NULL.
        if (req.unitPrice() == null) {
            CatalogPrice cp = catalog
                .filter(c -> c.salesPrice() != null)
                .orElseThrow(() -> new UnknownPriceException(req.productSku()));
            assertCurrency(req.productSku(), orderCurrency, cp.currencyCode());
            return cp.salesPrice();
        }

        catalog
            .filter(c -> c.currencyCode() != null)
            .ifPresent(cp -> assertCurrency(req.productSku(), orderCurrency, cp.currencyCode()));
        return req.unitPrice();
    }

    private static void assertCurrency(String sku, String orderCurrency, String catalogCurrency) {
        if (!catalogCurrency.equalsIgnoreCase(orderCurrency)) {
            throw new CurrencyMismatchException(sku, orderCurrency, catalogCurrency);
        }
    }

}
