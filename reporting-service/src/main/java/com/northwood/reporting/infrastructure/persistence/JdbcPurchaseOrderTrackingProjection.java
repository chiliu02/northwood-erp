package com.northwood.reporting.infrastructure.persistence;


import com.northwood.finance.domain.events.SupplierPaymentMade;
import com.northwood.reporting.application.inbox.PurchaseOrderTrackingProjection;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPurchaseOrderTrackingProjection implements PurchaseOrderTrackingProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcPurchaseOrderTrackingProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcPurchaseOrderTrackingProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void createFromPurchaseOrder(
        UUID purchaseOrderHeaderId,
        String purchaseOrderNumber,
        UUID supplierId,
        String supplierName,
        String poStatus,
        String currencyCode,
        BigDecimal orderedAmount,
        UUID sourceWorkOrderId,
        Instant occurredAt,
        String actorUserId
    ) {
        LocalDate orderDate = occurredAt == null
            ? LocalDate.now()
            : occurredAt.atZone(ZoneId.systemDefault()).toLocalDate();
        BigDecimal ordered = orderedAmount == null ? BigDecimal.ZERO : orderedAmount;
        jdbc.update("""
            INSERT INTO reporting.purchase_order_tracking_view (
                purchase_order_header_id, purchase_order_number,
                supplier_id, supplier_name,
                po_status, order_date, currency_code,
                ordered_amount, outstanding_amount,
                receipt_status, invoice_status, payment_status, match_status,
                source_work_order_id,
                last_modified_by, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,
                      'not_received', 'not_invoiced', 'unpaid', 'not_matched',
                      ?, ?, now())
            ON CONFLICT (purchase_order_header_id) DO UPDATE SET
                purchase_order_number = EXCLUDED.purchase_order_number,
                supplier_id = EXCLUDED.supplier_id,
                supplier_name = EXCLUDED.supplier_name,
                po_status = CASE
                    WHEN purchase_order_tracking_view.po_status = 'pending'
                        THEN EXCLUDED.po_status
                    ELSE purchase_order_tracking_view.po_status
                END,
                order_date = EXCLUDED.order_date,
                currency_code = EXCLUDED.currency_code,
                ordered_amount = EXCLUDED.ordered_amount,
                outstanding_amount = EXCLUDED.ordered_amount - purchase_order_tracking_view.paid_amount,
                receipt_status = CASE
                    WHEN purchase_order_tracking_view.received_amount = 0 THEN 'not_received'
                    WHEN purchase_order_tracking_view.received_amount >= EXCLUDED.ordered_amount THEN 'received'
                    ELSE 'partially_received'
                END,
                invoice_status = CASE
                    WHEN purchase_order_tracking_view.invoiced_amount = 0 THEN 'not_invoiced'
                    WHEN purchase_order_tracking_view.invoiced_amount >= EXCLUDED.ordered_amount THEN 'invoiced'
                    ELSE 'partially_invoiced'
                END,
                source_work_order_id = COALESCE(EXCLUDED.source_work_order_id, purchase_order_tracking_view.source_work_order_id),
                last_modified_by = COALESCE(EXCLUDED.last_modified_by, purchase_order_tracking_view.last_modified_by),
                updated_at = now()
            """,
            purchaseOrderHeaderId, purchaseOrderNumber,
            supplierId, supplierName,
            poStatus == null ? "sent" : poStatus,
            Date.valueOf(orderDate),
            currencyCode == null ? Currencies.AUD : currencyCode,
            ordered, ordered,
            sourceWorkOrderId,
            actorUserId
        );
        log.info("seeded po_tracking for {} ({}) ordered={} sourceWorkOrder={}",
            purchaseOrderNumber, purchaseOrderHeaderId, orderedAmount, sourceWorkOrderId);
    }

    @Override
    public Optional<UUID> findSourceWorkOrderForPo(UUID purchaseOrderHeaderId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT source_work_order_id FROM reporting.purchase_order_tracking_view
                WHERE purchase_order_header_id = ?
                """,
                UUID.class, purchaseOrderHeaderId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public int countOpenForWorkOrder(UUID workOrderId) {
        Integer count = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM reporting.purchase_order_tracking_view
            WHERE source_work_order_id = ?
              AND po_status NOT IN ('received', 'paid', 'cancelled')
            """,
            Integer.class, workOrderId
        );
        return count == null ? 0 : count;
    }

    @Override
    @Transactional
    public void recordPoApproved(UUID purchaseOrderHeaderId, Instant approvedAt, String actorUserId) {
        int rows = jdbc.update("""
            UPDATE reporting.purchase_order_tracking_view
            SET po_status = CASE
                    WHEN po_status = 'draft' THEN 'sent'
                    ELSE po_status
                END,
                approved_at = ?,
                last_modified_by = COALESCE(?, last_modified_by),
                updated_at = now()
            WHERE purchase_order_header_id = ?
            """,
            Timestamp.from(approvedAt), actorUserId, purchaseOrderHeaderId
        );
        if (rows == 0) {
            log.warn(
                "PurchaseOrderApproved received for unknown purchase_order_header_id={} — reporting.purchase_order_tracking_view row missing, projection skipped (inbox redelivery will catch up once po-created lands)",
                purchaseOrderHeaderId
            );
        } else {
            log.info("flipped reporting.purchase_order_tracking_view po_status -> sent for po={} (approved_at={})",
                purchaseOrderHeaderId, approvedAt);
        }
    }

    @Override
    @Transactional
    public void recordGoodsReceived(
        UUID purchaseOrderHeaderId,
        UUID goodsReceiptHeaderId,
        BigDecimal receivedDelta,
        Instant occurredAt,
        String actorUserId
    ) {
        BigDecimal delta = receivedDelta == null ? BigDecimal.ZERO : receivedDelta;
        jdbc.update("""
            INSERT INTO reporting.purchase_order_tracking_view (
                purchase_order_header_id, purchase_order_number,
                supplier_id, supplier_name,
                po_status, order_date, currency_code,
                ordered_amount, received_amount, outstanding_amount,
                receipt_status, invoice_status, payment_status, match_status,
                last_goods_receipt_header_id, last_modified_by, updated_at
            ) VALUES (?, '(pending)', '00000000-0000-0000-0000-000000000000', '(pending)',
                      'pending', CURRENT_DATE, 'AUD',
                      0, ?, 0,
                      'partially_received', 'not_invoiced', 'unpaid', 'not_matched',
                      ?, ?, now())
            ON CONFLICT (purchase_order_header_id) DO UPDATE SET
                received_amount = purchase_order_tracking_view.received_amount + EXCLUDED.received_amount,
                receipt_status = CASE
                    WHEN purchase_order_tracking_view.ordered_amount = 0 THEN 'partially_received'
                    WHEN (purchase_order_tracking_view.received_amount + EXCLUDED.received_amount) >= purchase_order_tracking_view.ordered_amount THEN 'received'
                    ELSE 'partially_received'
                END,
                po_status = CASE
                    WHEN purchase_order_tracking_view.ordered_amount = 0 THEN purchase_order_tracking_view.po_status
                    WHEN (purchase_order_tracking_view.received_amount + EXCLUDED.received_amount) >= purchase_order_tracking_view.ordered_amount THEN 'received'
                    ELSE 'partially_received'
                END,
                last_goods_receipt_header_id = EXCLUDED.last_goods_receipt_header_id,
                last_modified_by = COALESCE(EXCLUDED.last_modified_by, purchase_order_tracking_view.last_modified_by),
                updated_at = now()
            """,
            purchaseOrderHeaderId, delta, goodsReceiptHeaderId, actorUserId
        );
    }

    @Override
    @Transactional
    public void recordInvoiceApproved(
        UUID purchaseOrderHeaderId,
        UUID supplierInvoiceHeaderId,
        BigDecimal invoiceAmount,
        Instant occurredAt,
        String actorUserId
    ) {
        BigDecimal amount = invoiceAmount == null ? BigDecimal.ZERO : invoiceAmount;
        jdbc.update("""
            INSERT INTO reporting.purchase_order_tracking_view (
                purchase_order_header_id, purchase_order_number,
                supplier_id, supplier_name,
                po_status, order_date, currency_code,
                ordered_amount, invoiced_amount, outstanding_amount,
                receipt_status, invoice_status, payment_status, match_status,
                last_supplier_invoice_header_id, last_modified_by, updated_at
            ) VALUES (?, '(pending)', '00000000-0000-0000-0000-000000000000', '(pending)',
                      'pending', CURRENT_DATE, 'AUD',
                      0, ?, 0,
                      'not_received', 'partially_invoiced', 'unpaid', 'matched',
                      ?, ?, now())
            ON CONFLICT (purchase_order_header_id) DO UPDATE SET
                invoiced_amount = purchase_order_tracking_view.invoiced_amount + EXCLUDED.invoiced_amount,
                invoice_status = CASE
                    WHEN purchase_order_tracking_view.ordered_amount = 0 THEN 'partially_invoiced'
                    WHEN (purchase_order_tracking_view.invoiced_amount + EXCLUDED.invoiced_amount) >= purchase_order_tracking_view.ordered_amount THEN 'invoiced'
                    ELSE 'partially_invoiced'
                END,
                match_status = 'matched',
                last_supplier_invoice_header_id = EXCLUDED.last_supplier_invoice_header_id,
                last_modified_by = COALESCE(EXCLUDED.last_modified_by, purchase_order_tracking_view.last_modified_by),
                updated_at = now()
            """,
            purchaseOrderHeaderId, amount, supplierInvoiceHeaderId, actorUserId
        );
    }

    @Override
    @Transactional
    public void recordPayment(
        UUID purchaseOrderHeaderId,
        UUID paymentId,
        BigDecimal allocatedAmount,
        String invoiceStatusAfter,
        Instant occurredAt,
        String actorUserId
    ) {
        BigDecimal amount = allocatedAmount == null ? BigDecimal.ZERO : allocatedAmount;
        boolean fullySettled = SupplierPaymentMade.INVOICE_STATUS_PAID.equals(invoiceStatusAfter);
        jdbc.update("""
            INSERT INTO reporting.purchase_order_tracking_view (
                purchase_order_header_id, purchase_order_number,
                supplier_id, supplier_name,
                po_status, order_date, currency_code,
                ordered_amount, paid_amount, outstanding_amount,
                receipt_status, invoice_status, payment_status, match_status,
                last_payment_id, last_modified_by, updated_at
            ) VALUES (?, '(pending)', '00000000-0000-0000-0000-000000000000', '(pending)',
                      'pending', CURRENT_DATE, 'AUD',
                      0, ?, 0,
                      'not_received', 'not_invoiced', ?, 'not_matched',
                      ?, ?, now())
            ON CONFLICT (purchase_order_header_id) DO UPDATE SET
                paid_amount = purchase_order_tracking_view.paid_amount + EXCLUDED.paid_amount,
                outstanding_amount = purchase_order_tracking_view.ordered_amount - (purchase_order_tracking_view.paid_amount + EXCLUDED.paid_amount),
                payment_status = CASE
                    WHEN ? AND (purchase_order_tracking_view.paid_amount + EXCLUDED.paid_amount) >= purchase_order_tracking_view.ordered_amount THEN 'paid'
                    ELSE 'partially_paid'
                END,
                po_status = CASE
                    WHEN ? AND (purchase_order_tracking_view.paid_amount + EXCLUDED.paid_amount) >= purchase_order_tracking_view.ordered_amount THEN 'paid'
                    ELSE purchase_order_tracking_view.po_status
                END,
                last_payment_id = EXCLUDED.last_payment_id,
                last_modified_by = COALESCE(EXCLUDED.last_modified_by, purchase_order_tracking_view.last_modified_by),
                updated_at = now()
            """,
            purchaseOrderHeaderId, amount,
            fullySettled ? SupplierPaymentMade.INVOICE_STATUS_PAID : SupplierPaymentMade.INVOICE_STATUS_PARTIALLY_PAID,
            paymentId, actorUserId,
            fullySettled, fullySettled
        );
    }
}
