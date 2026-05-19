package com.northwood.purchasing.infrastructure.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.purchasing.domain.PurchaseRequisition;
import com.northwood.purchasing.domain.PurchaseRequisitionId;
import com.northwood.purchasing.domain.PurchaseRequisitionLine;
import com.northwood.purchasing.domain.PurchaseRequisitionRepository;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPurchaseRequisitionRepository implements PurchaseRequisitionRepository {

    private static final RowMapper<PurchaseRequisition> HEADER_MAPPER = (rs, n) -> PurchaseRequisition.reconstitute(
        PurchaseRequisitionId.of(rs.getObject("purchase_requisition_header_id", UUID.class)),
        rs.getString("requisition_number"),
        PurchaseRequisition.SourceType.fromDb(rs.getString("source_type")),
        rs.getObject("source_work_order_id", UUID.class),
        rs.getObject("source_product_id", UUID.class),
        PurchaseRequisition.Status.fromDb(rs.getString("status")),
        rs.getString("requested_by"),
        List.of(),
        rs.getLong("version")
    );

    private static final RowMapper<PurchaseRequisitionLine> LINE_MAPPER = (rs, n) -> {
        Date required = rs.getDate("required_date");
        return new PurchaseRequisitionLine(
            rs.getObject("purchase_requisition_line_id", UUID.class),
            rs.getInt("line_number"),
            rs.getObject("product_id", UUID.class),
            rs.getString("product_sku"),
            rs.getString("product_name"),
            rs.getBigDecimal("requested_quantity"),
            required == null ? null : required.toLocalDate(),
            rs.getObject("suggested_supplier_id", UUID.class),
            rs.getString("suggested_supplier_name"),
            PurchaseRequisition.LineStatus.fromDb(rs.getString("status"))
        );
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcPurchaseRequisitionRepository(JdbcTemplate jdbc, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<PurchaseRequisition> findById(PurchaseRequisitionId id) {
        List<PurchaseRequisition> matches = jdbc.query("""
            SELECT purchase_requisition_header_id, requisition_number,
                   source_type, source_product_id, source_work_order_id,
                   status, requested_by, version
            FROM purchasing.purchase_requisition_header
            WHERE purchase_requisition_header_id = ?
            """, HEADER_MAPPER, id.value());
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        PurchaseRequisition stub = matches.get(0);
        List<PurchaseRequisitionLine> lines = jdbc.query("""
            SELECT purchase_requisition_line_id, line_number,
                   product_id, product_sku, product_name,
                   requested_quantity, required_date,
                   suggested_supplier_id, suggested_supplier_name, status
            FROM purchasing.purchase_requisition_line
            WHERE purchase_requisition_header_id = ?
            ORDER BY line_number
            """, LINE_MAPPER, id.value());
        return Optional.of(PurchaseRequisition.reconstitute(
            stub.id(), stub.requisitionNumber(),
            stub.sourceType(), stub.sourceWorkOrderId(), stub.sourceProductId(),
            stub.status(), stub.requestedBy(), lines, stub.version()
        ));
    }

    @Override
    public List<PurchaseRequisition> findAll() {
        // Headers, newest first. Lines loaded per-row for the small-PR-volume
        // showcase; if PR volume grows past a few hundred, switch to a
        // dedicated SummaryQueryPort that joins COUNT(line) instead.
        List<PurchaseRequisition> headers = jdbc.query("""
            SELECT purchase_requisition_header_id, requisition_number,
                   source_type, source_product_id, source_work_order_id,
                   status, requested_by, version
            FROM purchasing.purchase_requisition_header
            ORDER BY created_at DESC
            """, HEADER_MAPPER);
        // Replace the empty line lists in the header stubs with the real lines.
        return headers.stream()
            .map(stub -> findById(stub.id()).orElse(stub))
            .toList();
    }

    @Override
    public void save(PurchaseRequisition pr) {
        String actor = currentUser.currentUsername().orElse(null);
        if (pr.version() == 0L) {
            insert(pr, actor);
        } else {
            update(pr, actor);
        }
        for (DomainEvent event : pr.pullPendingEvents()) {
            writeOutbox(event, actor);
        }
    }

    private void insert(PurchaseRequisition pr, String actor) {
        jdbc.update("""
            INSERT INTO purchasing.purchase_requisition_header (
                purchase_requisition_header_id, requisition_number,
                source_type, source_product_id, source_work_order_id,
                status, requested_by, version,
                created_by, last_modified_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            pr.id().value(), pr.requisitionNumber(),
            pr.sourceType().dbValue(), pr.sourceProductId(), pr.sourceWorkOrderId(),
            pr.status().dbValue(), pr.requestedBy(),
            1L,
            actor, actor
        );
        for (PurchaseRequisitionLine l : pr.lines()) {
            jdbc.update("""
                INSERT INTO purchasing.purchase_requisition_line (
                    purchase_requisition_line_id, purchase_requisition_header_id, line_number,
                    product_id, product_sku, product_name,
                    requested_quantity, required_date,
                    suggested_supplier_id, suggested_supplier_name, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                l.id(), pr.id().value(), l.lineNumber(),
                l.productId(), l.productSku(), l.productName(),
                l.requestedQuantity(),
                l.requiredDate() == null ? null : Date.valueOf(l.requiredDate()),
                l.suggestedSupplierId(), l.suggestedSupplierName(),
                l.status().dbValue()
            );
        }
    }

    private void update(PurchaseRequisition pr, String actor) {
        int rows = jdbc.update("""
            UPDATE purchasing.purchase_requisition_header
            SET status = ?,
                converted_at = CASE WHEN ? = 'converted' THEN now() ELSE converted_at END,
                version = version + 1,
                last_modified_by = ?
            WHERE purchase_requisition_header_id = ? AND version = ?
            """,
            pr.status().dbValue(), pr.status().dbValue(), actor, pr.id().value(), pr.version()
        );
        if (rows == 0) {
            throw new OptimisticLockingFailureException(
                "PurchaseRequisition " + pr.id().value() + " was modified by another transaction"
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
                PurchaseRequisition.AGGREGATE_TYPE,
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
