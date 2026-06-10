package com.northwood.reporting.infrastructure.persistence;


import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.reporting.application.inbox.SalesOrder360Projection;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSalesOrder360Projection implements SalesOrder360Projection {

    private static final Logger log = LoggerFactory.getLogger(JdbcSalesOrder360Projection.class);

    /** Sentinel zero-UUID used for stub rows (NOT NULL customer_id). */
    private static final UUID STUB_CUSTOMER_ID = new UUID(0L, 0L);

    /**
     * Stock lozenge shown for a zero-reserved-awaiting-supply reservation outcome,
     * replacing the misleading {@code 'failed'}: at reservation time nothing-from-stock
     * is never terminal (inventory has raised a replenishment; a to_order line is
     * zero-from-stock by design). True failure surfaces later via order rejection.
     */
    private static final String STOCK_STATUS_NOT_AVAILABLE = "not_available";

    private final JdbcTemplate jdbc;

    public JdbcSalesOrder360Projection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void createFromOrder(
        UUID salesOrderHeaderId,
        String orderNumber,
        UUID customerId,
        String customerName,
        LocalDate orderDate,
        LocalDate requestedDeliveryDate,
        String currencyCode,
        BigDecimal totalAmount,
        String paymentTerms,
        Instant occurredAt,
        String eventType,
        String actorUserId
    ) {
        // SalesOrderPlaced events produced before payment-terms support was
        // shipped won't carry paymentTerms — default to 'on_shipment' so the
        // CHECK on the read-side column (and the view's NOT NULL) holds.
        String pt = paymentTerms == null ? "on_shipment" : paymentTerms;
        jdbc.update("""
            INSERT INTO reporting.sales_order_360_view (
                sales_order_header_id, order_number,
                customer_id, customer_name,
                order_date, requested_delivery_date,
                order_status, stock_status, manufacturing_status,
                shipment_status, invoice_status, payment_status,
                payment_terms,
                currency_code, total_amount, outstanding_amount,
                last_event_type, last_event_at,
                last_modified_by
            ) VALUES (?, ?, ?, ?, ?, ?, 'submitted', 'pending', 'pending',
                      'pending', 'pending', 'pending',
                      ?,
                      ?, ?, ?, ?, ?, ?)
            ON CONFLICT (sales_order_header_id) DO UPDATE SET
                order_number = EXCLUDED.order_number,
                customer_id = EXCLUDED.customer_id,
                customer_name = EXCLUDED.customer_name,
                order_date = EXCLUDED.order_date,
                requested_delivery_date = EXCLUDED.requested_delivery_date,
                order_status = CASE
                    WHEN sales_order_360_view.order_status = 'pending'
                        THEN EXCLUDED.order_status
                    ELSE sales_order_360_view.order_status
                END,
                currency_code = EXCLUDED.currency_code,
                total_amount = EXCLUDED.total_amount,
                outstanding_amount = EXCLUDED.total_amount - sales_order_360_view.paid_amount,
                last_event_type = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_type
                    ELSE sales_order_360_view.last_event_type
                END,
                last_event_at = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_at
                    ELSE sales_order_360_view.last_event_at
                END,
                last_modified_by = COALESCE(EXCLUDED.last_modified_by, sales_order_360_view.last_modified_by),
                updated_at = now()
            """,
            salesOrderHeaderId, orderNumber,
            customerId, customerName,
            orderDate == null ? null : Date.valueOf(orderDate),
            requestedDeliveryDate == null ? null : Date.valueOf(requestedDeliveryDate),
            pt,
            Currencies.orBase(currencyCode),
            totalAmount == null ? BigDecimal.ZERO : totalAmount,
            totalAmount == null ? BigDecimal.ZERO : totalAmount,
            eventType,
            Timestamp.from(occurredAt == null ? Instant.now() : occurredAt),
            actorUserId
        );
        log.info("seeded sales_order_360 for {} ({}) total={} payment_terms={}",
            orderNumber, salesOrderHeaderId, totalAmount, pt);
    }

    @Override
    @Transactional
    public void recordAmendedTotal(UUID salesOrderHeaderId, BigDecimal newOrderTotal, Instant occurredAt, String eventType, String actorUserId) {
        BigDecimal total = newOrderTotal == null ? BigDecimal.ZERO : newOrderTotal;
        jdbc.update("""
            INSERT INTO reporting.sales_order_360_view (
                sales_order_header_id, order_number,
                customer_id, customer_name,
                order_date, order_status, stock_status,
                manufacturing_status, shipment_status,
                invoice_status, payment_status,
                currency_code, total_amount, outstanding_amount,
                last_event_type, last_event_at,
                last_modified_by
            ) VALUES (?, '(pending)', ?, '(pending)',
                      CURRENT_DATE, 'pending', 'pending',
                      'pending', 'pending',
                      'pending', 'pending',
                      'AUD', ?, ?, ?, ?, ?)
            ON CONFLICT (sales_order_header_id) DO UPDATE SET
                -- a line amendment changed the order total. Overwrite
                -- total_amount with the recomputed figure carried on the event
                -- and re-derive outstanding (total − already-paid). paid/invoiced
                -- are untouched (an amendment is pre-shipment; nothing is paid yet
                -- on the amendable path, but the subtraction stays correct if it is).
                total_amount = EXCLUDED.total_amount,
                outstanding_amount = EXCLUDED.total_amount - sales_order_360_view.paid_amount,
                last_event_type = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_type
                    ELSE sales_order_360_view.last_event_type
                END,
                last_event_at = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_at
                    ELSE sales_order_360_view.last_event_at
                END,
                last_modified_by = COALESCE(EXCLUDED.last_modified_by, sales_order_360_view.last_modified_by),
                updated_at = now()
            """,
            salesOrderHeaderId, STUB_CUSTOMER_ID, total, total, eventType,
            Timestamp.from(occurredAt == null ? Instant.now() : occurredAt),
            actorUserId
        );
        log.info("refreshed sales_order_360 total for {} → {} ({})", salesOrderHeaderId, total, eventType);
    }

    @Override
    @Transactional
    public void recordManufacturingCompleted(UUID salesOrderHeaderId, Instant occurredAt, String actorUserId) {
        jdbc.update("""
            INSERT INTO reporting.sales_order_360_view (
                sales_order_header_id, order_number,
                customer_id, customer_name,
                order_date, order_status, stock_status,
                manufacturing_status, shipment_status,
                invoice_status, payment_status,
                currency_code, total_amount, outstanding_amount,
                last_event_type, last_event_at,
                last_modified_by
            ) VALUES (?, '(pending)', ?, '(pending)',
                      CURRENT_DATE, 'pending', 'pending',
                      'completed', 'pending',
                      'pending', 'pending',
                      'AUD', 0, 0,
                      'manufacturing.WorkOrderManufacturingCompleted', ?, ?)
            ON CONFLICT (sales_order_header_id) DO UPDATE SET
                manufacturing_status = 'completed',
                last_event_type = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_type
                    ELSE sales_order_360_view.last_event_type
                END,
                last_event_at = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_at
                    ELSE sales_order_360_view.last_event_at
                END,
                last_modified_by = COALESCE(EXCLUDED.last_modified_by, sales_order_360_view.last_modified_by),
                updated_at = now()
            """,
            salesOrderHeaderId, STUB_CUSTOMER_ID,
            Timestamp.from(occurredAt == null ? Instant.now() : occurredAt),
            actorUserId
        );
    }

    @Override
    @Transactional
    public void recordStockReserved(UUID salesOrderHeaderId, String stockStatus, Instant occurredAt, String actorUserId) {
        // A zero-reserved reservation outcome ('failed') is not terminal — inventory
        // has already raised a replenishment and the saga parks awaiting supply (a
        // to_order line is zero-from-stock by design). Surface it as 'not_available'
        // rather than a red 'failed' lozenge; true failure surfaces later via order
        // rejection (order_status), not the stock axis.
        String displayStockStatus = StockReserved.STATUS_FAILED.equals(stockStatus)
            ? STOCK_STATUS_NOT_AVAILABLE
            : stockStatus;
        jdbc.update("""
            INSERT INTO reporting.sales_order_360_view (
                sales_order_header_id, order_number,
                customer_id, customer_name,
                order_date, order_status, stock_status,
                manufacturing_status, shipment_status,
                invoice_status, payment_status,
                currency_code, total_amount, outstanding_amount,
                last_event_type, last_event_at,
                last_modified_by
            ) VALUES (?, '(pending)', ?, '(pending)',
                      CURRENT_DATE, 'pending', ?,
                      'pending', 'pending',
                      'pending', 'pending',
                      'AUD', 0, 0,
                      'inventory.StockReserved', ?, ?)
            ON CONFLICT (sales_order_header_id) DO UPDATE SET
                -- Reflect the reservation outcome (reserved / partially_reserved
                -- / not_available). 'reserved' (full cover) is sticky — a later partial
                -- or stale event must not roll it back; a partial is otherwise
                -- overwritten by a subsequent full reservation (make-to-order
                -- re-reserves once the shortage is produced). The Stock lozenge
                -- rests at 'reserved' rather than mirroring Shipment's 'shipped'.
                stock_status = CASE
                    WHEN sales_order_360_view.stock_status = 'reserved'
                        THEN 'reserved'
                    ELSE EXCLUDED.stock_status
                END,
                last_event_type = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_type
                    ELSE sales_order_360_view.last_event_type
                END,
                last_event_at = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_at
                    ELSE sales_order_360_view.last_event_at
                END,
                last_modified_by = COALESCE(EXCLUDED.last_modified_by, sales_order_360_view.last_modified_by),
                updated_at = now()
            """,
            salesOrderHeaderId, STUB_CUSTOMER_ID, displayStockStatus,
            Timestamp.from(occurredAt == null ? Instant.now() : occurredAt),
            actorUserId
        );
    }

    @Override
    @Transactional
    public void recordReadyToShip(UUID salesOrderHeaderId, Instant occurredAt, String actorUserId) {
        jdbc.update("""
            INSERT INTO reporting.sales_order_360_view (
                sales_order_header_id, order_number,
                customer_id, customer_name,
                order_date, order_status, stock_status,
                manufacturing_status, shipment_status,
                invoice_status, payment_status,
                currency_code, total_amount, outstanding_amount,
                last_event_type, last_event_at,
                last_modified_by
            ) VALUES (?, '(pending)', ?, '(pending)',
                      CURRENT_DATE, 'ready_to_ship', 'pending',
                      'not_required', 'pending',
                      'pending', 'pending',
                      'AUD', 0, 0,
                      'sales.SalesOrderReadyToShip', ?, ?)
            ON CONFLICT (sales_order_header_id) DO UPDATE SET
                -- Forward-only: advance to ready_to_ship only from an earlier
                -- lifecycle state. Reporting consumes several topics, so a late
                -- event must never downgrade shipped/completed; 'cancelled'
                -- (terminal) is preserved by falling through to ELSE.
                order_status = CASE
                    WHEN sales_order_360_view.order_status IN ('pending', 'submitted')
                        THEN 'ready_to_ship'
                    ELSE sales_order_360_view.order_status
                END,
                -- A stock-covered order skips manufacturing (no work order, no
                -- completion event). Reaching ready_to_ship with manufacturing
                -- still 'pending' means it was never needed → not_required. A
                -- make-to-order order already had recordManufacturingCompleted
                -- set it 'completed', which is kept here.
                manufacturing_status = CASE
                    WHEN sales_order_360_view.manufacturing_status = 'pending'
                        THEN 'not_required'
                    ELSE sales_order_360_view.manufacturing_status
                END,
                -- Reaching ready_to_ship means every line is reserved (drawn from
                -- stock, produced, or pegged off a buy/make-to-order receipt), so
                -- the Stock lozenge is 'reserved' from here. This is the only path
                -- that flips a buy-to-order line out of 'not_available': its dedicated
                -- supply is pegged at goods-receipt and signalled to the saga via
                -- ReplenishmentFulfilled, not via a fresh inventory.StockReserved,
                -- so recordStockReserved never re-runs for it. A 'reserved' stays
                -- reserved (sticky); 'shipped' is never downgraded (cancelled is
                -- already excluded by the order_status guard above).
                stock_status = CASE
                    WHEN sales_order_360_view.order_status IN ('cancelled', 'rejected')
                        THEN sales_order_360_view.stock_status
                    WHEN sales_order_360_view.stock_status IN ('pending', 'not_available', 'failed', 'partially_reserved')
                        THEN 'reserved'
                    ELSE sales_order_360_view.stock_status
                END,
                last_event_type = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_type
                    ELSE sales_order_360_view.last_event_type
                END,
                last_event_at = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_at
                    ELSE sales_order_360_view.last_event_at
                END,
                last_modified_by = COALESCE(EXCLUDED.last_modified_by, sales_order_360_view.last_modified_by),
                updated_at = now()
            """,
            salesOrderHeaderId, STUB_CUSTOMER_ID,
            Timestamp.from(occurredAt == null ? Instant.now() : occurredAt),
            actorUserId
        );
    }

    @Override
    @Transactional
    public void recordCancellation(UUID salesOrderHeaderId, Instant occurredAt, String actorUserId) {
        jdbc.update("""
            INSERT INTO reporting.sales_order_360_view (
                sales_order_header_id, order_number,
                customer_id, customer_name,
                order_date, order_status, stock_status,
                manufacturing_status, shipment_status,
                invoice_status, payment_status,
                currency_code, total_amount, outstanding_amount,
                last_event_type, last_event_at,
                last_modified_by
            ) VALUES (?, '(pending)', ?, '(pending)',
                      CURRENT_DATE, 'cancelled', 'pending',
                      'pending', 'pending',
                      'pending', 'pending',
                      'AUD', 0, 0,
                      'sales.SalesOrderCompensated', ?, ?)
            ON CONFLICT (sales_order_header_id) DO UPDATE SET
                order_status = 'cancelled',
                -- Compensation released any reservation this order held, so a
                -- 'reserved' Stock lozenge becomes 'released'. Untouched when stock
                -- was never reserved (failed/pending) — nothing to give back.
                stock_status = CASE
                    WHEN sales_order_360_view.stock_status = 'reserved'
                        THEN 'released'
                    ELSE sales_order_360_view.stock_status
                END,
                -- A cancelled order that already took money was a paid prepayment
                -- or deposit (the only invoice a still-cancellable, pre-shipment
                -- order can have), and finance always returns that cash — Dr 2110
                -- / Cr 1000 — via SalesOrderCancellationRefundHandler. The Payment
                -- lozenge therefore reads 'refunded'. Derived rather than driven by
                -- a finance event: no CustomerRefunded event exists, and the
                -- paid_amount > 0 ⇒ refund invariant is exact for cancellable
                -- orders. See finance SalesOrderCancellationRefundHandler.
                payment_status = CASE
                    WHEN sales_order_360_view.paid_amount > 0
                        THEN 'refunded'
                    ELSE sales_order_360_view.payment_status
                END,
                last_event_type = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_type
                    ELSE sales_order_360_view.last_event_type
                END,
                last_event_at = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_at
                    ELSE sales_order_360_view.last_event_at
                END,
                last_modified_by = COALESCE(EXCLUDED.last_modified_by, sales_order_360_view.last_modified_by),
                updated_at = now()
            """,
            salesOrderHeaderId, STUB_CUSTOMER_ID,
            Timestamp.from(occurredAt == null ? Instant.now() : occurredAt),
            actorUserId
        );
    }

    @Override
    @Transactional
    public void recordShipment(UUID salesOrderHeaderId, boolean orderFullyShipped, Instant occurredAt, String actorUserId) {
        // Stub-row (shipment projected before placement): a partial shipment must
        // leave order_status pickable + advanceable, so 'ready_to_ship' rather
        // than a terminal — the eventual full shipment's CASE advances it.
        String stubOrderStatus = orderFullyShipped ? "shipped" : "ready_to_ship";
        String stubShipmentStatus = orderFullyShipped ? "shipped" : "partially_shipped";
        jdbc.update("""
            INSERT INTO reporting.sales_order_360_view (
                sales_order_header_id, order_number,
                customer_id, customer_name,
                order_date, order_status, stock_status,
                manufacturing_status, shipment_status,
                invoice_status, payment_status,
                currency_code, total_amount, outstanding_amount,
                last_event_type, last_event_at,
                last_modified_by
            ) VALUES (?, '(pending)', ?, '(pending)',
                      CURRENT_DATE, ?, 'pending',
                      'pending', ?,
                      'pending', 'pending',
                      'AUD', 0, 0,
                      'sales.SalesOrderShipped', ?, ?)
            ON CONFLICT (sales_order_header_id) DO UPDATE SET
                -- shipment_status forward-only: 'shipped' is never downgraded to
                -- 'partially_shipped' by an out-of-order/late event.
                shipment_status = CASE
                    WHEN sales_order_360_view.shipment_status = 'shipped' THEN 'shipped'
                    WHEN ?::boolean THEN 'shipped'
                    ELSE 'partially_shipped'
                END,
                -- order_status advances ONLY when the order is fully shipped. A
                -- prepaid order (paid in full before shipment) goes straight to
                -- 'completed'; otherwise 'shipped' (on_shipment/deposit owe the
                -- post-ship balance — recordPayment completes them once paid). A
                -- PARTIAL shipment leaves order_status unchanged so the order stays
                -- 'ready_to_ship' and pickable for the backorder. Preserves
                -- completed/cancelled via the ELSE.
                order_status = CASE
                    WHEN ?::boolean
                      AND sales_order_360_view.order_status IN ('pending', 'submitted', 'ready_to_ship')
                      AND sales_order_360_view.total_amount > 0
                      AND sales_order_360_view.total_amount - sales_order_360_view.paid_amount <= 0
                        THEN 'completed'
                    WHEN ?::boolean
                      AND sales_order_360_view.order_status IN ('pending', 'submitted', 'ready_to_ship')
                        THEN 'shipped'
                    ELSE sales_order_360_view.order_status
                END,
                last_event_type = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_type
                    ELSE sales_order_360_view.last_event_type
                END,
                last_event_at = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_at
                    ELSE sales_order_360_view.last_event_at
                END,
                last_modified_by = COALESCE(EXCLUDED.last_modified_by, sales_order_360_view.last_modified_by),
                updated_at = now()
            """,
            salesOrderHeaderId, STUB_CUSTOMER_ID,
            stubOrderStatus, stubShipmentStatus,
            Timestamp.from(occurredAt == null ? Instant.now() : occurredAt),
            actorUserId,
            orderFullyShipped, orderFullyShipped, orderFullyShipped
        );
    }

    @Override
    @Transactional
    public void recordInvoice(UUID salesOrderHeaderId, BigDecimal invoiced, Instant occurredAt, String actorUserId) {
        BigDecimal amount = invoiced == null ? BigDecimal.ZERO : invoiced;
        jdbc.update("""
            INSERT INTO reporting.sales_order_360_view (
                sales_order_header_id, order_number,
                customer_id, customer_name,
                order_date, order_status, stock_status,
                manufacturing_status, shipment_status,
                invoice_status, payment_status,
                currency_code, total_amount, invoiced_amount, outstanding_amount,
                last_event_type, last_event_at,
                last_modified_by
            ) VALUES (?, '(pending)', ?, '(pending)',
                      CURRENT_DATE, 'pending', 'pending',
                      'pending', 'pending',
                      'invoiced', 'pending',
                      'AUD', 0, ?, 0,
                      'finance.CustomerInvoiceCreated', ?, ?)
            ON CONFLICT (sales_order_header_id) DO UPDATE SET
                invoice_status = 'invoiced',
                invoiced_amount = sales_order_360_view.invoiced_amount + EXCLUDED.invoiced_amount,
                outstanding_amount = sales_order_360_view.total_amount - sales_order_360_view.paid_amount,
                last_event_type = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_type
                    ELSE sales_order_360_view.last_event_type
                END,
                last_event_at = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_at
                    ELSE sales_order_360_view.last_event_at
                END,
                last_modified_by = COALESCE(EXCLUDED.last_modified_by, sales_order_360_view.last_modified_by),
                updated_at = now()
            """,
            salesOrderHeaderId, STUB_CUSTOMER_ID, amount,
            Timestamp.from(occurredAt == null ? Instant.now() : occurredAt),
            actorUserId
        );
    }

    @Override
    @Transactional
    public void recordPayment(
        UUID salesOrderHeaderId,
        BigDecimal allocated,
        String invoiceStatusAfter,
        Instant occurredAt,
        String actorUserId
    ) {
        BigDecimal amount = allocated == null ? BigDecimal.ZERO : allocated;
        String paymentStatus = CustomerPaymentReceived.INVOICE_STATUS_PAID.equals(invoiceStatusAfter)
            ? CustomerPaymentReceived.INVOICE_STATUS_PAID
            : CustomerPaymentReceived.INVOICE_STATUS_PARTIALLY_PAID;
        jdbc.update("""
            INSERT INTO reporting.sales_order_360_view (
                sales_order_header_id, order_number,
                customer_id, customer_name,
                order_date, order_status, stock_status,
                manufacturing_status, shipment_status,
                invoice_status, payment_status,
                currency_code, total_amount, paid_amount, outstanding_amount,
                last_event_type, last_event_at,
                last_modified_by
            ) VALUES (?, '(pending)', ?, '(pending)',
                      CURRENT_DATE, 'pending', 'pending',
                      'pending', 'pending',
                      'pending', ?,
                      'AUD', 0, ?, 0,
                      'finance.CustomerPaymentReceived', ?, ?)
            ON CONFLICT (sales_order_header_id) DO UPDATE SET
                -- Payment lozenge tracks the ORDER, not a single invoice. A deposit
                -- order's deposit invoice settles to 'paid' while the order's
                -- balance is still outstanding — order-level that is 'partially_paid'
                -- until paid_amount covers total_amount. (The invoice-level value
                -- EXCLUDED.payment_status only seeds a stub row in the INSERT path.)
                payment_status = CASE
                    WHEN sales_order_360_view.total_amount > 0
                      AND sales_order_360_view.total_amount
                          - (sales_order_360_view.paid_amount + EXCLUDED.paid_amount) <= 0
                        THEN 'paid'
                    WHEN (sales_order_360_view.paid_amount + EXCLUDED.paid_amount) > 0
                        THEN 'partially_paid'
                    ELSE 'pending'
                END,
                -- Completion requires BOTH full settlement AND a posted shipment,
                -- so 'completed' only ever advances from 'shipped'. For on-shipment
                -- terms the post-ship invoice is the last event, so paying it while
                -- already 'shipped' completes the order here. A deposit (partial)
                -- never satisfies the full-settlement test at all. PREPAYMENT pays
                -- in full up front — before the goods reserve or ship — so it must
                -- NOT complete here (order_status is still 'submitted'/'ready_to_ship'
                -- at that point); the order stays at its fulfilment stage and
                -- recordShipment carries it to 'completed' once shipped. Forward-only
                -- and cancelled-preserving (advances only from 'shipped').
                order_status = CASE
                    WHEN sales_order_360_view.total_amount > 0
                      AND sales_order_360_view.total_amount
                          - (sales_order_360_view.paid_amount + EXCLUDED.paid_amount) <= 0
                      AND sales_order_360_view.order_status = 'shipped'
                        THEN 'completed'
                    ELSE sales_order_360_view.order_status
                END,
                paid_amount = sales_order_360_view.paid_amount + EXCLUDED.paid_amount,
                outstanding_amount = sales_order_360_view.total_amount - (sales_order_360_view.paid_amount + EXCLUDED.paid_amount),
                last_event_type = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_type
                    ELSE sales_order_360_view.last_event_type
                END,
                last_event_at = CASE
                    WHEN sales_order_360_view.last_event_at IS NULL
                      OR EXCLUDED.last_event_at > sales_order_360_view.last_event_at
                    THEN EXCLUDED.last_event_at
                    ELSE sales_order_360_view.last_event_at
                END,
                last_modified_by = COALESCE(EXCLUDED.last_modified_by, sales_order_360_view.last_modified_by),
                updated_at = now()
            """,
            salesOrderHeaderId, STUB_CUSTOMER_ID, paymentStatus, amount,
            Timestamp.from(occurredAt == null ? Instant.now() : occurredAt),
            actorUserId
        );
    }
}
