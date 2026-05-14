package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.purchasing.application.inbox.PurchaseOrderReceiptProjection;
import com.northwood.purchasing.domain.PurchaseOrder;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPurchaseOrderReceiptProjection implements PurchaseOrderReceiptProjection {

    private final JdbcTemplate jdbc;

    public JdbcPurchaseOrderReceiptProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReceiptOutcome recordReceipt(UUID purchaseOrderHeaderId, List<ReceiptLine> lines) {
        for (ReceiptLine rl : lines) {
            if (rl.purchaseOrderLineId() == null) {
                continue;
            }
            jdbc.update("""
                UPDATE purchasing.purchase_order_line
                   SET received_quantity = received_quantity + ?
                 WHERE purchase_order_line_id = ?
                """,
                rl.receivedQuantity(), rl.purchaseOrderLineId()
            );
        }

        Boolean fullyReceived = jdbc.queryForObject("""
            SELECT NOT EXISTS (
                SELECT 1 FROM purchasing.purchase_order_line
                WHERE purchase_order_header_id = ?
                  AND received_quantity < ordered_quantity
            )
            """, Boolean.class, purchaseOrderHeaderId);

        BigDecimal totalReceived = jdbc.queryForObject("""
            SELECT COALESCE(SUM(received_quantity * unit_price), 0)
            FROM purchasing.purchase_order_line
            WHERE purchase_order_header_id = ?
            """, BigDecimal.class, purchaseOrderHeaderId);

        boolean fully = Boolean.TRUE.equals(fullyReceived);
        String poStatus = fully ? PurchaseOrder.RECEIVED : PurchaseOrder.PARTIALLY_RECEIVED;
        jdbc.update("""
            UPDATE purchasing.purchase_order_header
               SET status = ?, received_amount = ?, version = version + 1
             WHERE purchase_order_header_id = ?
            """,
            poStatus, totalReceived, purchaseOrderHeaderId
        );

        return new ReceiptOutcome(fully, totalReceived);
    }
}
