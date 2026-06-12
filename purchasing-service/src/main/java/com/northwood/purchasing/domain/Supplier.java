package com.northwood.purchasing.domain;

import com.northwood.purchasing.domain.events.SupplierDetailsChanged;
import com.northwood.purchasing.domain.events.SupplierRegistered;
import com.northwood.purchasing.domain.events.SupplierStatusChanged;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Supplier master aggregate. Owns identity ({@code supplier_code}, immutable),
 * contact details, and lifecycle status ({@code active} | {@code inactive} |
 * {@code blocked}). Mirrors the {@code sales.Customer} master-data aggregate:
 * intent-named mutators emit events captured by the application service for
 * outbox publication.
 *
 * <p>Promoted from a read-only reference model on 2026-06-03 when supplier
 * onboarding / editing became a user story (add + change status + edit details).
 */
public final class Supplier {

    /** Supplier lifecycle status. Mirrors the CHECK on {@code purchasing.supplier.status}. */
    public enum Status {
        ACTIVE("active"),
        INACTIVE("inactive"),
        BLOCKED("blocked");

        private final String code;

        Status(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Status fromCode(String value) {
            for (Status s : values()) {
                if (s.code.equals(value)) return s;
            }
            throw Assert.unknownValue("supplier status", value);
        }
    }

    /** Wire-format aggregate-type stamped onto {@code purchasing.outbox_message.aggregate_type}. */
    public static final String AGGREGATE_TYPE = PurchasingAggregateTypes.SUPPLIER;

    private final SupplierId id;
    private final String supplierCode;  // identity — never changes
    private String name;
    private String email;
    private String phone;
    private String address;
    private Status status;
    private final long version;

    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private Supplier(
        SupplierId id, String supplierCode, String name,
        String email, String phone, String address,
        Status status, long version
    ) {
        this.id = id;
        this.supplierCode = supplierCode;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.address = address;
        this.status = status;
        this.version = version;
    }

    /** Factory — onboard a new supplier (lands {@code active}). Emits {@link SupplierRegistered}. */
    public static Supplier register(
        String supplierCode, String name,
        String email, String phone, String address
    ) {
        Assert.notBlank(supplierCode, "supplierCode required");
        Assert.notBlank(name, "name required");
        SupplierId id = SupplierId.of(UUID.randomUUID());
        Supplier s = new Supplier(id, supplierCode, name, email, phone, address, Status.ACTIVE, 0L);
        s.pendingEvents.add(new SupplierRegistered(
            UUID.randomUUID(), id.value(),
            supplierCode, name, email, phone, address, Status.ACTIVE.code(),
            Instant.now()
        ));
        return s;
    }

    /** Reconstitute from persistence. Emits nothing. */
    public static Supplier reconstitute(
        SupplierId id, String supplierCode, String name,
        String email, String phone, String address,
        Status status, long version
    ) {
        return new Supplier(id, supplierCode, name, email, phone, address, status, version);
    }

    /**
     * Change lifecycle status (active ⇄ inactive ⇄ blocked). No-op suppression
     * on an unchanged status. Emits {@link SupplierStatusChanged}.
     */
    public void changeStatus(Status newStatus, String reason) {
        Assert.notNull(newStatus, "newStatus required");
        if (newStatus == this.status) return;
        Status old = this.status;
        this.status = newStatus;
        pendingEvents.add(new SupplierStatusChanged(
            UUID.randomUUID(), id.value(), old.code(), newStatus.code(), reason, Instant.now()
        ));
    }

    /**
     * Update editable details (name / email / phone / address). No-op
     * suppression on identical values. Emits {@link SupplierDetailsChanged}.
     */
    public void updateDetails(String newName, String newEmail, String newPhone, String newAddress) {
        Assert.notBlank(newName, "name required");
        if (Objects.equals(this.name, newName)
            && Objects.equals(this.email, newEmail)
            && Objects.equals(this.phone, newPhone)
            && Objects.equals(this.address, newAddress)) {
            return;
        }
        this.name = newName;
        this.email = newEmail;
        this.phone = newPhone;
        this.address = newAddress;
        pendingEvents.add(new SupplierDetailsChanged(
            UUID.randomUUID(), id.value(), newName, newEmail, newPhone, newAddress, Instant.now()
        ));
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    public SupplierId id()         { return id; }
    public String supplierCode()   { return supplierCode; }
    public String name()           { return name; }
    public String email()          { return email; }
    public String phone()          { return phone; }
    public String address()        { return address; }
    public Status status()         { return status; }
    public long version()          { return version; }
    public boolean isActive()      { return status == Status.ACTIVE; }
}
