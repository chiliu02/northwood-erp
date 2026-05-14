package com.northwood.finance.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record CustomerInvoiceLine(
    UUID id,
    int lineNumber,
    UUID salesOrderLineId,
    UUID productId,
    String productSku,
    String productName,
    BigDecimal quantity,
    BigDecimal unitPrice,
    BigDecimal taxRate,
    BigDecimal taxAmount,
    BigDecimal lineTotal
) {}
