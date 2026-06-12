package com.northwood.inventory.application.dto;

import com.northwood.inventory.domain.Shipment;
import java.util.List;
import java.util.UUID;

/** Read-side projection of {@link Shipment} for the wire layer. */
public record ShipmentView(
    UUID id,
    String shipmentNumber,
    UUID salesOrderHeaderId,
    String salesOrderNumber,
    UUID customerId,
    String customerName,
    UUID warehouseId,
    String warehouseCode,
    String status,
    List<ShipmentLineView> lines,
    long version
) {
    public static ShipmentView from(Shipment s) {
        List<ShipmentLineView> lineViews = s.lines().stream()
            .map(ShipmentLineView::from)
            .toList();
        return new ShipmentView(
            s.id().value(), s.shipmentNumber(), s.salesOrderHeaderId(), s.salesOrderNumber(),
            s.customerId(), s.customerName(),
            s.warehouseId(), s.warehouseCode(),
            s.status().code(), lineViews, s.version()
        );
    }
}
