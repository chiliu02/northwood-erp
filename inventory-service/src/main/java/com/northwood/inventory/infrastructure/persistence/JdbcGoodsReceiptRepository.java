package com.northwood.inventory.infrastructure.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.inventory.domain.GoodsReceipt;
import com.northwood.inventory.domain.GoodsReceiptId;
import com.northwood.inventory.domain.GoodsReceiptLine;
import com.northwood.inventory.domain.GoodsReceiptRepository;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.messaging.OutboxTraceHeaders;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGoodsReceiptRepository implements GoodsReceiptRepository {

    private static final RowMapper<GoodsReceipt> HEADER_MAPPER = (rs, n) -> GoodsReceipt.reconstitute(
        GoodsReceiptId.of(rs.getObject("goods_receipt_header_id", UUID.class)),
        rs.getString("goods_receipt_number"),
        rs.getObject("purchase_order_header_id", UUID.class),
        rs.getString("purchase_order_number"),
        rs.getObject("supplier_id", UUID.class),
        rs.getString("supplier_name"),
        rs.getObject("warehouse_id", UUID.class),
        null,
        GoodsReceipt.Status.fromCode(rs.getString("status")),
        List.of(),
        rs.getLong("version")
    );

    private static final RowMapper<GoodsReceiptLine> LINE_MAPPER = (rs, n) -> new GoodsReceiptLine(
        rs.getObject("goods_receipt_line_id", UUID.class),
        rs.getObject("purchase_order_line_id", UUID.class),
        rs.getObject("product_id", UUID.class),
        rs.getString("product_sku"),
        rs.getString("product_name"),
        rs.getBigDecimal("received_quantity"),
        rs.getBigDecimal("unit_cost"),
        rs.getBigDecimal("line_cost")
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcGoodsReceiptRepository(JdbcTemplate jdbc, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<GoodsReceipt> findById(GoodsReceiptId id) {
        List<GoodsReceipt> matches = jdbc.query("""
            SELECT goods_receipt_header_id, goods_receipt_number,
                   purchase_order_header_id, purchase_order_number, supplier_id, supplier_name,
                   warehouse_id, status, version
            FROM inventory.goods_receipt_header
            WHERE goods_receipt_header_id = ?
            """, HEADER_MAPPER, id.value());
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        GoodsReceipt stub = matches.get(0);
        List<GoodsReceiptLine> lines = jdbc.query("""
            SELECT goods_receipt_line_id, purchase_order_line_id,
                   product_id, product_sku, product_name,
                   received_quantity, unit_cost, line_cost
            FROM inventory.goods_receipt_line
            WHERE goods_receipt_header_id = ?
            ORDER BY goods_receipt_line_id
            """, LINE_MAPPER, id.value());
        return Optional.of(GoodsReceipt.reconstitute(
            stub.id(), stub.goodsReceiptNumber(),
            stub.purchaseOrderHeaderId(), stub.purchaseOrderNumber(), stub.supplierId(), stub.supplierName(),
            stub.warehouseId(), stub.warehouseCode(),
            stub.status(), lines, stub.version()
        ));
    }

    @Override
    public List<GoodsReceipt> findAllHeaders() {
        return jdbc.query("""
            SELECT goods_receipt_header_id, goods_receipt_number,
                   purchase_order_header_id, purchase_order_number, supplier_id, supplier_name,
                   warehouse_id, status, version
            FROM inventory.goods_receipt_header
            ORDER BY created_at DESC
            """, HEADER_MAPPER);
    }

    @Override
    public void save(GoodsReceipt gr) {
        String actor = currentUser.currentUsername().orElse(null);
        if (gr.version() == 0L) {
            insert(gr, actor);
        } else {
            throw new IllegalStateException("GoodsReceipt is post-only in phase 3; updates not supported");
        }
        for (DomainEvent event : gr.pullPendingEvents()) {
            writeOutbox(event, actor);
        }
    }

    private void insert(GoodsReceipt gr, String actor) {
        jdbc.update("""
            INSERT INTO inventory.goods_receipt_header (
                goods_receipt_header_id, goods_receipt_number,
                purchase_order_header_id, purchase_order_number, supplier_id, supplier_name,
                warehouse_id, status, version, posted_at,
                created_by, last_modified_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            gr.id().value(), gr.goodsReceiptNumber(),
            gr.purchaseOrderHeaderId(), gr.purchaseOrderNumber(), gr.supplierId(), gr.supplierName(),
            gr.warehouseId(), gr.status().code(),
            1L,
            gr.status() == GoodsReceipt.Status.POSTED ? Timestamp.from(Instant.now()) : null,
            actor, actor
        );
        for (GoodsReceiptLine l : gr.lines()) {
            jdbc.update("""
                INSERT INTO inventory.goods_receipt_line (
                    goods_receipt_line_id, goods_receipt_header_id, purchase_order_line_id,
                    product_id, product_sku, product_name,
                    received_quantity, unit_cost, line_cost
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                l.id(), gr.id().value(), l.purchaseOrderLineId(),
                l.productId(), l.productSku(), l.productName(),
                l.receivedQuantity(),
                l.unitCost() == null ? BigDecimal.ZERO : l.unitCost(),
                l.lineCost() == null ? BigDecimal.ZERO : l.lineCost()
            );
        }
    }

    private void writeOutbox(DomainEvent event, String actor) {
        try {
            jdbc.update("""
                INSERT INTO inventory.outbox_message (
                    outbox_message_id, aggregate_type, aggregate_id,
                    event_type, event_version, payload, headers, status, actor_user_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'pending', ?)
                """,
                event.eventId(),
                GoodsReceipt.AGGREGATE_TYPE,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event), OutboxTraceHeaders.currentJson(),
                actor
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise " + event.eventType(), e);
        }
    }

}
