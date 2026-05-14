package com.northwood.product.api.dto;

import java.util.UUID;

/**
 * {@code bomHeaderId} can be null to indicate "no active BOM" (e.g.
 * the SKU is no longer buildable). The aggregate accepts the null and
 * emits {@code BomActivated} with {@code newBomHeaderId=null}.
 */
public record ActivateBomRequest(UUID bomHeaderId) {}
