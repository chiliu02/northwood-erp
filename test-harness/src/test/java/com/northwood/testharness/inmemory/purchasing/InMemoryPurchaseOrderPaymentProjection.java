package com.northwood.testharness.inmemory.purchasing;

import com.northwood.purchasing.application.inbox.PurchaseOrderPaymentProjection;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class InMemoryPurchaseOrderPaymentProjection implements PurchaseOrderPaymentProjection {

    private final Map<UUID, BigDecimal> invoicedByPo = new HashMap<>();
    private final Map<UUID, BigDecimal> paidByPo = new HashMap<>();
    private final Map<UUID, Boolean> fullyPaid = new HashMap<>();

    @Override
    public void addInvoicedAmount(UUID purchaseOrderHeaderId, BigDecimal invoicedAmount) {
        invoicedByPo.merge(purchaseOrderHeaderId, invoicedAmount, BigDecimal::add);
    }

    @Override
    public void markFullyPaid(UUID purchaseOrderHeaderId) {
        fullyPaid.put(purchaseOrderHeaderId, true);
    }

    @Override
    public void addPartialPayment(UUID purchaseOrderHeaderId, BigDecimal allocatedAmount) {
        paidByPo.merge(purchaseOrderHeaderId, allocatedAmount, BigDecimal::add);
    }

    public boolean isFullyPaid(UUID purchaseOrderHeaderId) {
        return fullyPaid.getOrDefault(purchaseOrderHeaderId, false);
    }

    public BigDecimal paidAmount(UUID purchaseOrderHeaderId) {
        return paidByPo.getOrDefault(purchaseOrderHeaderId, BigDecimal.ZERO);
    }

    public BigDecimal invoicedAmount(UUID purchaseOrderHeaderId) {
        return invoicedByPo.getOrDefault(purchaseOrderHeaderId, BigDecimal.ZERO);
    }
}
