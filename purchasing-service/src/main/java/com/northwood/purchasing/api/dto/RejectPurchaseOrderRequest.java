package com.northwood.purchasing.api.dto;

/**
 * Reject-PO request. The actor ({@code cancelledBy}) is taken from the
 * authenticated principal server-side (not the client), so only an optional
 * free-text {@code reason} remains.
 */
public record RejectPurchaseOrderRequest(
    String reason
) {}
