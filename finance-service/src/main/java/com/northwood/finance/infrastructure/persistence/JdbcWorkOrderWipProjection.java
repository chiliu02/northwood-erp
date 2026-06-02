package com.northwood.finance.infrastructure.persistence;

import com.northwood.finance.application.inbox.WorkOrderWipProjection;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC projection for {@code finance.work_order_wip}. Each mutator is a
 * single idempotent upsert; the affected-row count drives the idempotency gate
 * the two posting handlers read (a conditional {@code ON CONFLICT … WHERE} that
 * matches no row returns 0).
 */
@Repository
public class JdbcWorkOrderWipProjection implements WorkOrderWipProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcWorkOrderWipProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcWorkOrderWipProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public boolean chargeRawMaterials(UUID workOrderId, BigDecimal amount) {
        int rows = jdbc.update("""
            INSERT INTO finance.work_order_wip (work_order_id, wip_value, materials_charged_at)
            VALUES (?, ?, now())
            ON CONFLICT (work_order_id) DO UPDATE SET
                wip_value            = finance.work_order_wip.wip_value + EXCLUDED.wip_value,
                materials_charged_at = now()
            WHERE finance.work_order_wip.materials_charged_at IS NULL
            """,
            workOrderId, amount
        );
        boolean charged = rows > 0;
        if (!charged) {
            log.debug("work_order {} already charged raw materials to WIP — skipping (idempotent)", workOrderId);
        }
        return charged;
    }

    @Override
    @Transactional
    public void rollInSubAssemblies(UUID workOrderId, BigDecimal amount) {
        jdbc.update("""
            INSERT INTO finance.work_order_wip (work_order_id, wip_value)
            VALUES (?, ?)
            ON CONFLICT (work_order_id) DO UPDATE SET
                wip_value = finance.work_order_wip.wip_value + EXCLUDED.wip_value
            """,
            workOrderId, amount
        );
    }

    @Override
    @Transactional
    public boolean markCompleted(UUID workOrderId, UUID finishedProductId) {
        int rows = jdbc.update("""
            INSERT INTO finance.work_order_wip (work_order_id, finished_product_id, completed_at)
            VALUES (?, ?, now())
            ON CONFLICT (work_order_id) DO UPDATE SET
                completed_at        = now(),
                finished_product_id = COALESCE(finance.work_order_wip.finished_product_id, EXCLUDED.finished_product_id)
            WHERE finance.work_order_wip.completed_at IS NULL
            """,
            workOrderId, finishedProductId
        );
        boolean completed = rows > 0;
        if (!completed) {
            log.debug("work_order {} WIP already settled at completion — skipping (idempotent)", workOrderId);
        }
        return completed;
    }
}
