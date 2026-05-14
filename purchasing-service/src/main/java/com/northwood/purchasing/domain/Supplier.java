package com.northwood.purchasing.domain;

/**
 * Supplier read model. Phase 1 only needs a lookup endpoint plus a default
 * supplier for shortage-driven requisitions. Edit commands (create / update /
 * status changes) land in a later slice when supplier onboarding becomes a
 * user story.
 */
public final class Supplier {

    // Status constants — wire-format strings stored in purchasing.supplier.status.
    public static final String ACTIVE = "active";
    public static final String INACTIVE = "inactive";

    private final SupplierId id;
    private final String supplierCode;
    private final String name;
    private final String status;

    public Supplier(SupplierId id, String supplierCode, String name, String status) {
        this.id = id;
        this.supplierCode = supplierCode;
        this.name = name;
        this.status = status;
    }

    public SupplierId id()         { return id; }
    public String supplierCode()   { return supplierCode; }
    public String name()           { return name; }
    public String status()         { return status; }
    public boolean isActive()      { return ACTIVE.equals(status); }
}
