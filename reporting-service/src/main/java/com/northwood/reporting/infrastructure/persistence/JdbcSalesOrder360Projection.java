package com.northwood.reporting.infrastructure.persistence;


import com.northwood.finance.domain.events.CustomerPaymentReceived;
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
        Instant occurredAt,
        String eventType,
        String actorUserId
    ) {
        jdbc.update("""
            INSERT INTO reporting.sales_order_360_view (
                sales_order_header_id, order_number,
                customer_id, customer_name,
                order_date, requested_delivery_date,
                order_status, stock_status, manufacturing_status,
                shipment_status, invoice_status, payment_status,
                currency_code, total_amount, outstanding_amount,
                last_event_type, last_event_at,
                last_modified_by
            ) VALUES (?, ?, ?, ?, ?, ?, 'submitted', 'pending', 'pending',
                      'pending', 'pending', 'pending',
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
            Currencies.orBase(currencyCode),
            totalAmount == null ? BigDecimal.ZERO : totalAmount,
            totalAmount == null ? BigDecimal.ZERO : totalAmount,
            eventType,
            Timestamp.from(occurredAt == null ? Instant.now() : occurredAt),
            actorUserId
        );
        log.info("seeded sales_order_360 for {} ({}) total={}",
            orderNumber, salesOrderHeaderId, totalAmount);
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
                      'pending', 'pending',
                      'pending', 'pending',
                      'AUD', 0, 0,
                      'sales.SalesOrderReadyToShip', ?, ?)
            ON CONFLICT (sales_order_header_id) DO UPDATE SET
                order_status = CASE
                    WHEN sales_order_360_view.order_status = 'cancelled'
                        THEN 'cancelled'
                    ELSE 'ready_to_ship'
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
    public void recordShipment(UUID salesOrderHeaderId, Instant occurredAt, String actorUserId) {
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
                      'pending', 'shipped',
                      'pending', 'pending',
                      'AUD', 0, 0,
                      'inventory.ShipmentPosted', ?, ?)
            ON CONFLICT (sales_order_header_id) DO UPDATE SET
                shipment_status = 'shipped',
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
                payment_status = EXCLUDED.payment_status,
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
