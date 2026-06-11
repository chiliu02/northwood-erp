package com.northwood.sales.application;

import com.northwood.sales.application.CustomerLookup.CustomerSummary;
import com.northwood.sales.application.ProductCardLookup.CatalogPrice;
import com.northwood.sales.application.dto.AddOrderLineCommand;
import com.northwood.sales.application.dto.CancelOrderCommand;
import com.northwood.sales.application.dto.ChangeOrderLineQuantityCommand;
import com.northwood.sales.application.dto.ChangeOrderLineUnitPriceCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.application.dto.RemoveOrderLineCommand;
import com.northwood.sales.application.dto.SalesOrderView;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.PaymentTerms;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrder.ShippedLineInput;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderLine;
import com.northwood.sales.domain.SalesOrderRepository;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.application.exception.BadRequestException;
import com.northwood.shared.application.exception.ConflictException;
import com.northwood.shared.application.exception.NotFoundException;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.domain.LineNumbering;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    public static class CustomerNotFoundException extends NotFoundException {
        public static final String CODE = "CUSTOMER_NOT_FOUND";
        private final String customerCode;
        public CustomerNotFoundException(String customerCode) {
            super(CODE, "Customer not found: " + customerCode);
            this.customerCode = customerCode;
        }
        public String customerCode() { return customerCode; }
        @Override public Map<String, Object> params() { return Map.of("customerCode", customerCode); }
    }

    /**
     * Thrown when {@link #placeOrder(PlaceOrderCommand)} resolves the customer
     * but its {@code status != 'active'} (i.e. {@code 'inactive'} or
     * {@code 'blocked'}). Mapped to HTTP 409 by the shared advice — the customer
     * exists, the order request is malformed against current state.
     */
    public static class CustomerInactiveException extends ConflictException {
        public static final String CODE = "CUSTOMER_INACTIVE";
        private final String customerCode;
        private final Customer.Status status;
        public CustomerInactiveException(String customerCode, Customer.Status status) {
            super(CODE, "Customer " + customerCode + " is " + status.dbValue() + "; cannot accept new orders");
            this.customerCode = customerCode;
            this.status = status;
        }
        public String customerCode() { return customerCode; }
        public Customer.Status status() { return status; }
        @Override public Map<String, Object> params() {
            return Map.of("customerCode", customerCode, "status", status.dbValue());
        }
    }

    public static class OrderNotFoundException extends NotFoundException {
        public static final String CODE = "ORDER_NOT_FOUND";
        private final UUID orderId;
        public OrderNotFoundException(UUID id) {
            super(CODE, "Sales order not found: " + id);
            this.orderId = id;
        }
        public UUID orderId() { return orderId; }
        @Override public Map<String, Object> params() { return Map.of("orderId", orderId); }
    }

    public static class SagaNotFoundException extends NotFoundException {
        public static final String CODE = "FULFILMENT_SAGA_NOT_FOUND";
        private final UUID salesOrderHeaderId;
        public SagaNotFoundException(UUID salesOrderHeaderId) {
            super(CODE, "No fulfilment saga for sales order " + salesOrderHeaderId);
            this.salesOrderHeaderId = salesOrderHeaderId;
        }
        public UUID salesOrderHeaderId() { return salesOrderHeaderId; }
        @Override public Map<String, Object> params() { return Map.of("salesOrderHeaderId", salesOrderHeaderId); }
    }

    /**
     * Application-layer wrapper around the domain
     * {@link SalesOrder.OrderNotCancellableException}. Controllers catch this
     * (HTTP 409) instead of reaching into {@code domain/} for the exception
     * type — keeps the api → application → domain layering clean.
     */
    public static class OrderNotCancellableException extends ConflictException {
        public static final String CODE = "ORDER_NOT_CANCELLABLE";
        public OrderNotCancellableException(Throwable cause) {
            super(CODE, cause.getMessage(), cause);
        }
        @Override public Map<String, Object> params() {
            // Domain exception's English message carries the receiver's state
            // (status + which transition was rejected) — surfaced as 'detail'
            // until a typed domain exception is introduced.
            return Map.of("detail", getMessage());
        }
    }

    public static class UnknownPriceException extends BadRequestException {
        public static final String CODE = "UNKNOWN_CATALOG_PRICE";
        private final String sku;
        public UnknownPriceException(String sku) {
            super(CODE, "No catalog price for sku=" + sku + "; provide unitPrice on the line or wait for the projection to catch up");
            this.sku = sku;
        }
        public String sku() { return sku; }
        @Override public Map<String, Object> params() { return Map.of("sku", sku); }
    }

    public static class CurrencyMismatchException extends BadRequestException {
        public static final String CODE = "CURRENCY_MISMATCH";
        private final String sku;
        private final String orderCurrency;
        private final String catalogCurrency;
        public CurrencyMismatchException(String sku, String orderCurrency, String catalogCurrency) {
            super(CODE, "Order currency " + orderCurrency + " does not match catalog currency "
                + catalogCurrency + " for sku=" + sku);
            this.sku = sku;
            this.orderCurrency = orderCurrency;
            this.catalogCurrency = catalogCurrency;
        }
        public String sku() { return sku; }
        public String orderCurrency() { return orderCurrency; }
        public String catalogCurrency() { return catalogCurrency; }
        @Override public Map<String, Object> params() {
            return Map.of("sku", sku, "orderCurrency", orderCurrency, "catalogCurrency", catalogCurrency);
        }
    }

    public static class ProductDiscontinuedException extends ConflictException {
        public static final String CODE = "PRODUCT_DISCONTINUED";
        private final String sku;
        private final java.time.Instant discontinuedAt;
        public ProductDiscontinuedException(String sku, java.time.Instant discontinuedAt) {
            super(CODE, "Product sku=" + sku + " was discontinued at " + discontinuedAt
                + "; cannot accept new order lines for it");
            this.sku = sku;
            this.discontinuedAt = discontinuedAt;
        }
        public String sku() { return sku; }
        public java.time.Instant discontinuedAt() { return discontinuedAt; }
        @Override public Map<String, Object> params() {
            return Map.of("sku", sku, "discontinuedAt", discontinuedAt.toString());
        }
    }

    /**
     * Application-layer wrapper for the domain
     * {@link SalesOrder.OrderNotAmendableException} <i>and</i> the saga-state
     * window guard (lines can only be amended before stock is reserved). Both
     * surface as HTTP 409 with the reason in {@code detail}.
     */
    public static class OrderNotAmendableException extends ConflictException {
        public static final String CODE = "ORDER_NOT_AMENDABLE";
        public OrderNotAmendableException(String message) {
            super(CODE, message);
        }
        @Override public Map<String, Object> params() {
            return Map.of("detail", getMessage());
        }
    }

    public static class OrderLineNotFoundException extends NotFoundException {
        public static final String CODE = "ORDER_LINE_NOT_FOUND";
        private final UUID orderId;
        private final UUID lineId;
        public OrderLineNotFoundException(UUID orderId, UUID lineId) {
            super(CODE, "Sales order " + orderId + " has no active line " + lineId);
            this.orderId = orderId;
            this.lineId = lineId;
        }
        public UUID orderId() { return orderId; }
        public UUID lineId() { return lineId; }
        @Override public Map<String, Object> params() {
            return Map.of("orderId", orderId, "lineId", lineId);
        }
    }

    /** Thrown when a remove would leave the order with no live line — cancel the order instead. */
    public static class EmptyOrderNotAllowedException extends BadRequestException {
        public static final String CODE = "ORDER_LINE_LAST_REMOVAL";
        private final UUID orderId;
        public EmptyOrderNotAllowedException(UUID orderId) {
            super(CODE, "Sales order " + orderId
                + " has only one line left; removing it would empty the order — cancel the order instead");
            this.orderId = orderId;
        }
        public UUID orderId() { return orderId; }
        @Override public Map<String, Object> params() { return Map.of("orderId", orderId); }
    }

    /** Thrown when an amend's {@code If-Match} version is stale against the persisted order. */
    public static class OrderVersionConflictException extends ConflictException {
        public static final String CODE = "ORDER_VERSION_CONFLICT";
        private final UUID orderId;
        private final long expectedVersion;
        private final long actualVersion;
        public OrderVersionConflictException(UUID orderId, long expectedVersion, long actualVersion) {
            super(CODE, "Sales order " + orderId + " was modified concurrently (expected version "
                + expectedVersion + ", current " + actualVersion + "); reload and retry");
            this.orderId = orderId;
            this.expectedVersion = expectedVersion;
            this.actualVersion = actualVersion;
        }
        public UUID orderId() { return orderId; }
        public long expectedVersion() { return expectedVersion; }
        public long actualVersion() { return actualVersion; }
        @Override public Map<String, Object> params() {
            return Map.of("orderId", orderId, "expectedVersion", expectedVersion, "actualVersion", actualVersion);
        }
    }

    /**
     * Fulfilment-saga states in which line amendment is currently permitted.
     * {@code started} / {@code awaiting_release} — nothing reserved
     * yet, a pure sales-side edit. {@code stock_reservation_requested} /
     * {@code ready_to_ship} — inventory reconciles the change
     * incrementally (reserve the added line / release the removed line / delta
     * the quantity) and the saga reconciles its outstanding-line set via
     * {@code SalesOrderLineReservationChanged}. {@code stock_reservation_incomplete}
     * — amend a shortage-parked order: inventory reconciles incrementally
     * (a removed short line cancels its in-flight replenishment) and the saga's
     * outstanding-set plumbing absorbs the change. The finance-invoiced window
     * (a pre-shipment invoice exists) stays out.
     */
    private static final Set<String> AMENDABLE_SAGA_STATES = Set.of(
        SalesOrderFulfilmentSaga.STARTED,
        SalesOrderFulfilmentSaga.AWAITING_RELEASE,
        SalesOrderFulfilmentSaga.STOCK_RESERVATION_REQUESTED,
        SalesOrderFulfilmentSaga.STOCK_RESERVATION_INCOMPLETE,
        SalesOrderFulfilmentSaga.SUPPLY_SECURED
    );

    /**
     * Reserve-phase states that are <em>past</em> invoice creation for
     * prepayment/deposit orders (finance guard). These terms raise their
     * invoice up front — before stock reservation — so by the time a
     * prepayment/deposit order reaches any of these states a pre-shipment invoice
     * already exists and amending the lines would desync it (post-invoice
     * amendment needs the credit-note flow). On-shipment orders carry no
     * invoice until shipment, so they stay amendable here. {@code started} is
     * excluded — it precedes the invoice request for every term.
     */
    private static final Set<String> POST_UPFRONT_INVOICE_STATES = Set.of(
        SalesOrderFulfilmentSaga.AWAITING_RELEASE,
        SalesOrderFulfilmentSaga.STOCK_RESERVATION_REQUESTED,
        SalesOrderFulfilmentSaga.STOCK_RESERVATION_INCOMPLETE,
        SalesOrderFulfilmentSaga.SUPPLY_SECURED
    );

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
     * outbox. The fulfilment saga is moved to {@code 'compensating'}; inventory
     * acks via {@code InventorySalesOrderCancellationApplied} (the sole
     * compensation ack), after which the saga advances to
     * {@code 'compensated'}.
     *
     * <p>Cancellable up to (and including) {@code ready_to_ship}; once
     * {@code goods_shipped} or beyond, the credit-note / return-goods flow
     * applies (out of scope).
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

    // ============================================================
    // Line amendment (post-placement, pre-reservation — this slice)
    // ============================================================

    /**
     * Add a line to an existing order. Reuses {@link #resolveUnitPrice} so the
     * new line gets the same catalog-price / currency-match / discontinued-SKU
     * validation as placement. Returns the reloaded order (fresh version) for
     * the {@code ETag}.
     */
    @Transactional
    public SalesOrderView addLine(AddOrderLineCommand command) {
        SalesOrder order = loadOrder(command.salesOrderHeaderId());
        assertAmendableWindow(order, command.expectedVersion());

        BigDecimal resolvedUnitPrice = resolveUnitPrice(
            new OrderLine(command.productId(), command.productSku(), command.productName(),
                command.orderedQuantity(), command.unitPrice(), command.taxRate()),
            order.currencyCode());
        try {
            order.addLine(command.productId(), command.productSku(), command.productName(),
                command.orderedQuantity(), resolvedUnitPrice, command.taxRate());
        } catch (SalesOrder.OrderNotAmendableException e) {
            throw new OrderNotAmendableException(e.getMessage());
        }
        salesOrders.save(order);
        return reload(command.salesOrderHeaderId());
    }

    /** Change a line's ordered quantity (price unchanged). */
    @Transactional
    public SalesOrderView changeLineQuantity(ChangeOrderLineQuantityCommand command) {
        SalesOrder order = loadOrder(command.salesOrderHeaderId());
        assertAmendableWindow(order, command.expectedVersion());
        SalesOrderLine line = activeLineOrThrow(order, command.salesOrderLineId());
        applyChange(order, command.salesOrderLineId(), command.orderedQuantity(), line.unitPrice());
        salesOrders.save(order);
        return reload(command.salesOrderHeaderId());
    }

    /**
     * Override a line's unit price (quantity unchanged). The override is
     * currency-checked against the catalog the same way placement validates an
     * explicit price.
     */
    @Transactional
    public SalesOrderView changeLineUnitPrice(ChangeOrderLineUnitPriceCommand command) {
        SalesOrder order = loadOrder(command.salesOrderHeaderId());
        assertAmendableWindow(order, command.expectedVersion());
        SalesOrderLine line = activeLineOrThrow(order, command.salesOrderLineId());
        BigDecimal resolvedUnitPrice = resolveUnitPrice(
            new OrderLine(line.productId(), line.productSku(), line.productName(),
                line.orderedQuantity(), command.unitPrice(), line.taxRate()),
            order.currencyCode());
        applyChange(order, command.salesOrderLineId(), line.orderedQuantity(), resolvedUnitPrice);
        salesOrders.save(order);
        return reload(command.salesOrderHeaderId());
    }

    /** Soft-remove a line. Rejected (400) if it would leave the order empty. */
    @Transactional
    public SalesOrderView removeLine(RemoveOrderLineCommand command) {
        SalesOrder order = loadOrder(command.salesOrderHeaderId());
        assertAmendableWindow(order, command.expectedVersion());
        activeLineOrThrow(order, command.salesOrderLineId());
        long liveLines = order.lines().stream().filter(l -> !l.isCancelled()).count();
        if (liveLines <= 1) {
            throw new EmptyOrderNotAllowedException(command.salesOrderHeaderId());
        }
        try {
            order.removeLine(command.salesOrderLineId());
        } catch (SalesOrder.OrderNotAmendableException e) {
            throw new OrderNotAmendableException(e.getMessage());
        } catch (SalesOrder.LineNotFoundException e) {
            throw new OrderLineNotFoundException(command.salesOrderHeaderId(), command.salesOrderLineId());
        }
        salesOrders.save(order);
        return reload(command.salesOrderHeaderId());
    }

    private void applyChange(SalesOrder order, UUID lineId, BigDecimal newQuantity, BigDecimal newUnitPrice) {
        try {
            order.changeLine(lineId, newQuantity, newUnitPrice);
        } catch (SalesOrder.OrderNotAmendableException e) {
            throw new OrderNotAmendableException(e.getMessage());
        } catch (SalesOrder.LineNotFoundException e) {
            throw new OrderLineNotFoundException(order.id().value(), lineId);
        }
    }

    private SalesOrder loadOrder(UUID salesOrderHeaderId) {
        return salesOrders.findById(SalesOrderId.of(salesOrderHeaderId))
            .orElseThrow(() -> new OrderNotFoundException(salesOrderHeaderId));
    }

    private SalesOrderView reload(UUID salesOrderHeaderId) {
        return SalesOrderView.from(loadOrder(salesOrderHeaderId));
    }

    private SalesOrderLine activeLineOrThrow(SalesOrder order, UUID lineId) {
        return order.lines().stream()
            .filter(l -> l.lineId().equals(lineId) && !l.isCancelled())
            .findFirst()
            .orElseThrow(() -> new OrderLineNotFoundException(order.id().value(), lineId));
    }

    /**
     * Two-part amendable-window guard: the optimistic-concurrency check (the
     * caller's {@code If-Match} version must match the persisted order) and the
     * saga-state window (lines amend only before stock is reserved, this slice).
     * The domain {@link SalesOrder#assertAmendable} status check is the coarse
     * backstop applied inside each mutator.
     */
    private void assertAmendableWindow(SalesOrder order, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != order.version()) {
            throw new OrderVersionConflictException(order.id().value(), expectedVersion, order.version());
        }
        String state = sagaManager.currentState(order.id().value())
            .orElseThrow(() -> new SagaNotFoundException(order.id().value()));
        if (!AMENDABLE_SAGA_STATES.contains(state)) {
            throw new OrderNotAmendableException(
                "Sales order " + order.id().value() + " fulfilment is at '" + state
                + "'; lines can only be amended before goods ship");
        }
        // Finance guard: prepayment/deposit orders invoice up front, so
        // once past `started` they already carry a pre-shipment invoice — block
        // the amendment (post-invoice amendment needs the credit-note flow).
        if (POST_UPFRONT_INVOICE_STATES.contains(state) && hasUpfrontInvoiceTerms(order.id().value())) {
            throw new OrderNotAmendableException(
                "Sales order " + order.id().value() + " has a pre-shipment invoice "
                + "(prepayment/deposit terms); its lines can't be amended once invoiced");
        }
    }

    private boolean hasUpfrontInvoiceTerms(UUID salesOrderHeaderId) {
        String terms = sagaManager.currentPaymentTerms(salesOrderHeaderId).orElse(null);
        return PaymentTerms.PREPAYMENT.dbValue().equals(terms) || PaymentTerms.DEPOSIT.dbValue().equals(terms);
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

        // Per-order override falls back to customer's default when omitted (the
        // common case). Validated against the enum so a typo on the API surfaces
        // as 400, not a CHECK violation at INSERT time.
        PaymentTerms paymentTerms = command.paymentTerms() == null
            ? customer.defaultPaymentTerms()
            : PaymentTerms.fromDb(command.paymentTerms());

        // Deposit orders carry an up-front percent (default 50%); every other
        // term must not carry one. Validated here so a bad value is a 400,
        // not a CHECK violation at INSERT time.
        BigDecimal depositPercent = resolveDepositPercent(paymentTerms, command.depositPercent());

        SalesOrder order = SalesOrder.place(
            command.orderNumber(),
            customer.customerId(),
            customer.customerCode(),
            customer.customerName(),
            command.requestedDeliveryDate(),
            command.currencyCode(),
            BigDecimal.ONE,
            paymentTerms,
            depositPercent,
            lines
        );

        salesOrders.save(order);

        // Stash payment_terms + need-by onto saga.data so the worker can branch
        // at started (on_shipment → existing StockReservationRequested path;
        // prepayment → PrepaymentInvoiceRequested), so applyCustomerPaymentReceived
        // can route full settlement to the right terminal (completed vs prepaid),
        // and so requestStockReservation can compute the planning-time-fence
        // release date (need-by − max line fence). Inline JSON to keep
        // ObjectMapper out of this service; matches FulfilmentSagaData's wire
        // shape. dbValue() is "on_shipment" / "prepayment" and LocalDate.toString()
        // is ISO yyyy-MM-dd — no quoting concerns.
        String needByJson = command.requestedDeliveryDate() == null
            ? "null"
            : "\"" + command.requestedDeliveryDate() + "\"";
        String dataJson = "{\"paymentTerms\":\"" + paymentTerms.dbValue() + "\","
            + "\"requestedDeliveryDate\":" + needByJson + "}";
        sagaManager.insertStarted(order.id().value(), dataJson);

        return SalesOrderView.from(order);
    }

    /**
     * Resolve + validate the up-front deposit fraction. Deposit orders default
     * to 50% when none is supplied; the value must be in (0, 100]. Any other
     * payment term must not carry a percent (a provided one is a 400).
     */
    private static BigDecimal resolveDepositPercent(PaymentTerms terms, BigDecimal requested) {
        if (terms != PaymentTerms.DEPOSIT) {
            Assert.argument(requested == null,
                "deposit_percent is only valid for deposit orders (payment_terms=deposit)");
            return null;
        }
        BigDecimal pct = requested == null ? new BigDecimal("50") : requested;
        Assert.argument(pct.signum() > 0 && pct.compareTo(new BigDecimal("100")) < 0,
            "deposit_percent must be in (0, 100) — a deposit is strictly partial; got " + pct);
        return pct;
    }

    /**
     * Loads the {@link SalesOrder}, calls {@code recordShipped(...)} (accumulates
     * per-line shipped quantity, moves the header to {@code shipped} /
     * {@code partially_shipped}, emits {@code SalesOrderShipped}), and saves — the
     * repository persists the line shipment progress + drains the pending event
     * onto the outbox in the same transaction. Called from the inbox handler that
     * consumes inventory's {@code ShipmentPosted}. Returns the aggregate's
     * {@link SalesOrder.ShipmentOutcome} so the handler can gate the saga on
     * whether this shipment completed the order.
     */
    @Transactional
    public SalesOrder.ShipmentOutcome recordShipped(
        UUID salesOrderHeaderId,
        UUID shipmentHeaderId,
        String shipmentNumber,
        LocalDate shipmentDate,
        List<ShippedLineInput> shippedLines
    ) {
        SalesOrder order = salesOrders.findById(SalesOrderId.of(salesOrderHeaderId))
            .orElseThrow(() -> new IllegalStateException(
                "No sales_order_header for sales_order_header_id=" + salesOrderHeaderId));
        SalesOrder.ShipmentOutcome outcome =
            order.recordShipped(shipmentHeaderId, shipmentNumber, shipmentDate, new ArrayList<>(shippedLines));
        salesOrders.save(order);
        return outcome;
    }

    /**
     * Reflect inventory's stock-reservation outcome onto the {@link SalesOrder}
     * aggregate: loads the order, applies the per-line reserved
     * quantities ({@code line_number → reservedQuantity}) onto the lines, and
     * saves — the repository persists the line reservation band + the re-derived
     * header status in the same transaction. Called from the inbox handler that
     * consumes inventory's {@code StockReserved}, replacing the former blind
     * {@code markStatus(IN_FULFILMENT)} projection write: the header now reaches
     * {@code reserved} / {@code partially_reserved} as the fold of reserved lines,
     * not an independent column write. No-op-safe if the order vanished (defensive — the saga
     * exists for an existing order).
     */
    @Transactional
    public void recordReservation(UUID salesOrderHeaderId, Map<Integer, BigDecimal> reservedByLineNumber) {
        SalesOrder order = salesOrders.findById(SalesOrderId.of(salesOrderHeaderId))
            .orElseThrow(() -> new IllegalStateException(
                "No sales_order_header for sales_order_header_id=" + salesOrderHeaderId));
        order.recordReservation(reservedByLineNumber);
        salesOrders.save(order);
    }

    /**
     * Mark the order {@code completed} (a guarded aggregate transition,
     * replacing the former blind {@code markStatus(COMPLETED)}). Loads the order,
     * calls {@link SalesOrder#complete()} (which asserts it has fully shipped),
     * and saves. Called from the inbox handlers when the fulfilment saga reaches
     * its {@code completed} terminal (full settlement). No-op-safe if the order
     * vanished (defensive — the saga exists for an existing order).
     */
    @Transactional
    public void completeOrder(UUID salesOrderHeaderId) {
        SalesOrder order = salesOrders.findById(SalesOrderId.of(salesOrderHeaderId))
            .orElseThrow(() -> new IllegalStateException(
                "No sales_order_header for sales_order_header_id=" + salesOrderHeaderId));
        order.complete();
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
