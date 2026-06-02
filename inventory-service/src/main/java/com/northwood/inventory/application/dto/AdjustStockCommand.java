package com.northwood.inventory.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request to adjust on-hand stock for a single product.
 *
 * <p>{@link Mode} chooses how {@code value} is read; either way the service
 * persists the resulting {@code (N − current)} signed delta as a positive
 * magnitude + direction (the balance is always derived from the movement,
 * never overwritten):
 *
 * <ul>
 *   <li>{@link Mode#DELTA} — {@code value} is a signed change (e.g. {@code +25},
 *       {@code -10}); must be non-zero.</li>
 *   <li>{@link Mode#SET} — {@code value} is the target on-hand (must be
 *       {@code >= 0}); the service reads current on-hand inside the transaction
 *       and adjusts by the difference.</li>
 * </ul>
 */
public record AdjustStockCommand(
    @NotBlank @Size(max = 50) String adjustmentNumber,
    @NotNull UUID productId,
    @NotBlank String productSku,
    @NotBlank String productName,
    String warehouseCode,
    @NotNull Mode mode,
    @NotNull BigDecimal value,
    @NotBlank @Size(max = 500) String reason
) {
    public enum Mode { DELTA, SET }
}
