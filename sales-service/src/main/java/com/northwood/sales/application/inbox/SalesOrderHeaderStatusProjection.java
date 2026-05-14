package com.northwood.sales.application.inbox;

import java.util.UUID;

/**
 * Projects saga progress onto the {@code sales_order_header.status} column so
 * the GET endpoint reflects fulfilment state without reading the saga row.
 * Pure read-side write — no domain event emitted; the saga drives the
 * transition, this is just the column-on-the-aggregate echo.
 *
 * <p>Centralises the {@code UPDATE … SET status = ?, version = version + 1}
 * shape so the {@code version + 1} bump can't drift across call sites
 * (previously duplicated in three inbox handlers).
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcSalesOrderHeaderStatusProjection}.
 */
public interface SalesOrderHeaderStatusProjection {

    void markStatus(UUID salesOrderHeaderId, String headerStatus);
}
