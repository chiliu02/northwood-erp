package com.northwood.purchasing.api.dto;

/**
 * Approve-PO request. The approver is taken from the authenticated principal
 * server-side (not the client), so only an optional free-text {@code reason}
 * remains.
 */
public record ApprovePurchaseOrderRequest(
    String reason
) {}
