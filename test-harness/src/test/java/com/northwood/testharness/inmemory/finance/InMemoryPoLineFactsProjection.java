package com.northwood.testharness.inmemory.finance;

import com.northwood.finance.application.inbox.PoLineFactsProjection;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class InMemoryPoLineFactsProjection implements PoLineFactsProjection {

    private static final class Row {
        UUID purchaseOrderHeaderId;
        UUID supplierId;
        String supplierName;
        String currencyCode;
        UUID productId;
        String productSku;
        String productName;
        BigDecimal orderedQuantity;
        BigDecimal unitPrice;
        BigDecimal receivedQuantity = BigDecimal.ZERO;
        BigDecimal invoicedQuantity = BigDecimal.ZERO;
    }

    private final Map<UUID, Row> byPoLineId = new HashMap<>();

    @Override
    public void applyPoCreated(
        UUID purchaseOrderHeaderId, UUID supplierId, String supplierName,
        String currencyCode, UUID purchaseOrderLineId,
        UUID productId, String productSku, String productName,
        BigDecimal orderedQuantity, BigDecimal unitPrice
    ) {
        Row r = byPoLineId.computeIfAbsent(purchaseOrderLineId, k -> new Row());
        r.purchaseOrderHeaderId = purchaseOrderHeaderId;
        r.supplierId = supplierId;
        r.supplierName = supplierName;
        r.currencyCode = currencyCode;
        r.productId = productId;
        r.productSku = productSku;
        r.productName = productName;
        r.orderedQuantity = orderedQuantity;
        r.unitPrice = unitPrice;
    }

    @Override
    public void applyGoodsReceived(UUID purchaseOrderLineId, BigDecimal receivedQuantity) {
        Row r = byPoLineId.computeIfAbsent(purchaseOrderLineId, k -> new Row());
        r.receivedQuantity = (r.receivedQuantity == null ? BigDecimal.ZERO : r.receivedQuantity).add(receivedQuantity);
    }

    @Override
    public void bumpInvoiced(UUID purchaseOrderLineId, BigDecimal invoicedQuantity) {
        Row r = byPoLineId.computeIfAbsent(purchaseOrderLineId, k -> new Row());
        r.invoicedQuantity = (r.invoicedQuantity == null ? BigDecimal.ZERO : r.invoicedQuantity).add(invoicedQuantity);
    }

    @Override
    public LineFacts findByLineId(UUID purchaseOrderLineId) {
        Row r = byPoLineId.get(purchaseOrderLineId);
        if (r == null) return null;
        return new LineFacts(
            purchaseOrderLineId, r.purchaseOrderHeaderId, r.supplierId, r.supplierName,
            r.currencyCode, r.productId, r.productSku, r.productName,
            r.orderedQuantity, r.unitPrice, r.receivedQuantity, r.invoicedQuantity
        );
    }
}
