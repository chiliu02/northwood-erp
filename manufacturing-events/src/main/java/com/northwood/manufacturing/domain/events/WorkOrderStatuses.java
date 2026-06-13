package com.northwood.manufacturing.domain.events;

/**
 * Wire-format constants for {@code manufacturing.work_order.status}. Cross-service
 * consumers (reporting, sales) reference these constants so a producer-side rename
 * surfaces at compile time across services.
 *
 * <p>Producer-side enum is {@code manufacturing.WorkOrder.Status}; same wire format,
 * different access path per the cross-service contract rule
 * (see {@code docs/conventions.md} → <i>Cross-service wire-format constants</i>).
 *
 * <p>This is a constants holder, not an aggregate. It lives in {@code manufacturing-events}
 * because that's the cross-service contract jar — code in any service can depend on it
 * without violating the schema-per-service rule.
 */
public final class WorkOrderStatuses {

    public static final String RELEASED = "released";
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";
    public static final String CLOSED = "closed";
    public static final String CANCELLED = "cancelled";

    private WorkOrderStatuses() {}
}
