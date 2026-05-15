package com.northwood.purchasing.infrastructure.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.purchasing.domain.PurchaseOrder;
import com.northwood.purchasing.domain.PurchaseOrderId;
import com.northwood.purchasing.domain.PurchaseOrderLine;
import com.northwood.purchasing.domain.PurchaseOrderRepository;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPurchaseOrderRepository implements PurchaseOrderRepository {

    private static final RowMapper<PurchaseOrder> HEADER_MAPPER = (rs, n) -> PurchaseOrder.reconstitute(
        PurchaseOrderId.of(rs.getObject("purchase_order_header_id", UUID.class)),
        rs.getString("purchase_order_number"),
        rs.getObject("supplier_id", UUID.class),
        rs.getString("supplier_code"),
        rs.getString("supplier_name"),
        rs.getObject("purchase_requisition_header_id", UUID.class),
        rs.getString("currency_code"),
        rs.getBigDecimal("subtotal_amount"),
        rs.getBigDecimal("tax_amount"),
        rs.getBigDecimal("total_amount"),
        rs.getString("status"),
        List.of(),
        rs.getLong("version")
    );

    private static final RowMapper<PurchaseOrderLine> LINE_MAPPER = (rs, n) -> new PurchaseOrderLine(
        rs.getObject("purchase_order_line_id", UUID.class),
        rs.getInt("line_number"),
        rs.getObject("purchase_requisition_line_id", UUID.class),
        rs.getObject("product_id", UUID.class),
        rs.getString("product_sku"),
        rs.getString("product_name"),
        rs.getBigDecimal("ordered_quantity"),
        rs.getBigDecimal("unit_price"),
        rs.getBigDecimal("tax_rate"),
        rs.getBigDecimal("tax_amount"),
        rs.getBigDecimal("line_total"),
        rs.getString("status")
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcPurchaseOrderRepository(JdbcTemplate jdbc, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<PurchaseOrder> findById(PurchaseOrderId id) {
        List<PurchaseOrder> matches = jdbc.query("""
            SELECT purchase_order_header_id, purchase_order_number,
                   supplier_id, supplier_code, supplier_name,
                   purchase_requisition_header_id,
                   currency_code, subtotal_amount, tax_amount, total_amount,
                   status, version
            FROM purchasing.purchase_order_header
            WHERE purchase_order_header_id = ?
            """, HEADER_MAPPER, id.value());
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        PurchaseOrder stub = matches.get(0);
        List<PurchaseOrderLine> lines = jdbc.query("""
            SELECT purchase_order_line_id, line_number, purchase_requisition_line_id,
                   product_id, product_sku, product_name,
                   ordered_quantity, unit_price, tax_rate, tax_amount, line_total, status
            FROM purchasing.purchase_order_line
            WHERE purchase_order_header_id = ?
            ORDER BY line_number
            """, LINE_MAPPER, id.value());
        return Optional.of(PurchaseOrder.reconstitute(
            stub.id(), stub.purchaseOrderNumber(),
            stub.supplierId(), stub.supplierCode(), stub.supplierName(),
            stub.purchaseRequisitionHeaderId(),
            stub.currencyCode(),
            stub.subtotalAmount(), stub.taxAmount(), stub.totalAmount(),
            stub.status(), lines, stub.version()
        ));
    }

    @Override
    public void save(PurchaseOrder po) {
        String actor = currentUser.currentUsername().orElse(null);
        if (po.version() == 0L) {
            insert(po, actor);
        } else {
            update(po, actor);
        }
        for (DomainEvent event : po.pullPendingEvents()) {
            writeOutbox(event, actor);
        }
    }

    private void insert(PurchaseOrder po, String actor) {
        jdbc.update("""
            INSERT INTO purchasing.purchase_order_header (
                purchase_order_header_id, purchase_order_number,
                supplier_id, supplier_code, supplier_name,
                purchase_requisition_header_id,
                currency_code, subtotal_amount, tax_amount, total_amount,
                status, version,
                created_by, last_modified_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            po.id().value(), po.purchaseOrderNumber(),
            po.supplierId(), po.supplierCode(), po.supplierName(),
            po.purchaseRequisitionHeaderId(),
            po.currencyCode(), po.subtotalAmount(), po.taxAmount(), po.totalAmount(),
            po.status(), 1L,
            actor, actor
        );
        for (PurchaseOrderLine l : po.lines()) {
            jdbc.update("""
                INSERT INTO purchasing.purchase_order_line (
                    purchase_order_line_id, purchase_order_header_id, purchase_requisition_line_id,
                    line_number,
                    product_id, product_sku, product_name,
                    ordered_quantity, unit_price, tax_rate, tax_amount, line_total, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                l.id(), po.id().value(), l.purchaseRequisitionLineId(),
                l.lineNumber(),
                l.productId(), l.productSku(), l.productName(),
                l.orderedQuantity(), l.unitPrice(),
                l.taxRate() == null ? BigDecimal.ZERO : l.taxRate(),
                l.taxAmount() == null ? BigDecimal.ZERO : l.taxAmount(),
                l.lineTotal(),
                l.status()
            );
        }
    }

    private void update(PurchaseOrder po, String actor) {
        int rows = jdbc.update("""
            UPDATE purchasing.purchase_order_header
            SET status = ?, version = version + 1, last_modified_by = ?
            WHERE purchase_order_header_id = ? AND version = ?
            """,
            po.status(), actor, po.id().value(), po.version()
        );
        if (rows == 0) {
            throw new OptimisticLockingFailureException(
                "PurchaseOrder " + po.id().value() + " was modified by another transaction"
            );
        }
    }

    private void writeOutbox(DomainEvent event, String actor) {
        try {
            jdbc.update("""
                INSERT INTO purchasing.outbox_message (
                    outbox_message_id, aggregate_type, aggregate_id,
                    event_type, event_version, payload, status, actor_user_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'pending', ?)
                """,
                event.eventId(),
                PurchaseOrder.AGGREGATE_TYPE,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event),
                actor
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise " + event.eventType(), e);
        }
    }

}
