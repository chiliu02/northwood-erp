package com.northwood.purchasing.application.dto;

import java.util.List;
import java.util.UUID;

/**
 * Command to create a purchase requisition in response to an
 * {@code inventory.ReplenishmentRequested} event with
 * {@code targetService = "purchasing"}. Replaces the retired
 * {@code WorkOrderShortageCommand} — the manufacturing↔purchasing
 * operational edge is now mediated by inventory's
 * {@code ReplenishmentRequest}, so both reorder-point breaches AND
 * ex-WO-shortage triggers arrive through this single command.
 */
public record StockReplenishmentCommand(
    String requisitionNumber,
    UUID replenishmentRequestId,
    // Cross-saga key: the sales order whose demand triggered this
    // replenishment (carried on the consumed inventory.ReplenishmentRequested
    // for sales_order_shortage / order_pegged reasons). Null for reorder-point
    // breaches. Threaded into the purchase_to_pay_saga so its milestones stamp
    // northwood.sales_order_id; NOT persisted on the requisition / PO.
    UUID sourceSalesOrderHeaderId,
    List<RequisitionLineRequest> lines
) {}
