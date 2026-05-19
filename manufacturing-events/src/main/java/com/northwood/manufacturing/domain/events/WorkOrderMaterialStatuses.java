package com.northwood.manufacturing.domain.events;

/**
 * Wire-format constants for {@code manufacturing.work_order.material_status}. Same
 * cross-service contract role as {@link WorkOrderStatuses}; producer-side enum is
 * {@code manufacturing.WorkOrder.MaterialStatus}.
 */
public final class WorkOrderMaterialStatuses {

    public static final String NOT_CHECKED = "not_checked";
    public static final String RESERVATION_PENDING = "reservation_pending";
    public static final String RESERVED = "reserved";
    public static final String PARTIALLY_RESERVED = "partially_reserved";
    public static final String SHORTAGE = "shortage";
    public static final String ISSUED = "issued";

    private WorkOrderMaterialStatuses() {}
}
