package com.northwood.inventory.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CQRS read port for the stock reservations list view. Returns flat row
 * shapes with header + first-line summary; the list view doesn't need full
 * line hierarchies. JDBC adapter at
 * {@code infrastructure/persistence/JdbcStockReservationQueryPort}.
 */
public interface StockReservationQueryPort {

    List<ReservationRow> listAll();

    record ReservationRow(
        UUID stockReservationHeaderId,
        UUID salesOrderHeaderId,
        UUID workOrderId,
        UUID warehouseId,
        String status,
        int lineCount,
        BigDecimal totalRequestedQuantity,
        BigDecimal totalReservedQuantity,
        BigDecimal totalShortageQuantity,
        Instant createdAt
    ) {}
}
