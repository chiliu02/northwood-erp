package com.northwood.sales.infrastructure.persistence;

import com.northwood.sales.application.inbox.SalesOrderHeaderStatusProjection;
import com.northwood.sales.domain.SalesOrder;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSalesOrderHeaderStatusProjection implements SalesOrderHeaderStatusProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcSalesOrderHeaderStatusProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcSalesOrderHeaderStatusProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markStatus(UUID salesOrderHeaderId, SalesOrder.Status headerStatus) {
        int rows = jdbc.update(
            "UPDATE sales.sales_order_header SET status = ?, version = version + 1 WHERE sales_order_header_id = ?",
            headerStatus.dbValue(), salesOrderHeaderId
        );
        if (rows == 0) {
            log.warn("could not project status='{}' to order header: no row for sales_order_header_id={}",
                headerStatus.dbValue(), salesOrderHeaderId);
        }
    }
}
