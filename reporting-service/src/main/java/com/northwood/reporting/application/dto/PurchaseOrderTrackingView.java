package com.northwood.reporting.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PurchaseOrderTrackingView(
    UUID purchaseOrderHeaderId,
    String purchaseOrderNumber,
    UUID supplierId,
    String supplierName,
    String poStatus,
    LocalDate orderDate,
    LocalDate expectedReceiptDate,
    String currencyCode,
    BigDecimal orderedAmount,
    BigDecimal receivedAmount,
    BigDecimal invoicedAmount,
    BigDecimal paidAmount,
    BigDecimal outstandingAmount,
    String receiptStatus,
    String invoiceStatus,
    String paymentStatus,
    String matchStatus,
    UUID lastGoodsReceiptHeaderId,
    UUID lastSupplierInvoiceHeaderId,
    UUID lastPaymentId,
    Instant updatedAt
) {}