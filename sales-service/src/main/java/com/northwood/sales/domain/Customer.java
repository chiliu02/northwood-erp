package com.northwood.sales.domain;

import com.northwood.sales.domain.events.CustomerAddressChanged;
import com.northwood.sales.domain.events.CustomerContactChanged;
import com.northwood.sales.domain.events.CustomerDeactivated;
import com.northwood.sales.domain.events.CustomerNameChanged;
import com.northwood.sales.domain.events.CustomerRegistered;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Customer master aggregate. Owns identity (code), display name, contact,
 * and addresses. Status drives soft-delete: {@code active} | {@code inactive}
 * | {@code blocked}.
 *
 * <p><b>Snapshotted reference data — design decision (locked 2026-05-08).</b>
 * Sales orders capture {@code customer_id} + {@code customer_code} +
 * {@code customer_name} at placement time and never refresh them. A
 * subsequent {@link #changeName} therefore does NOT update existing orders
 * or reporting's {@code sales_order_360_view.customer_name} — that's the
 * audit-correct behaviour (the order shows the name at time of placement,
 * not what the customer is called today). The change event is still
 * emitted so a future consumer that wants to re-project (e.g. an
 * up-to-date directory view) can subscribe; today none does. See
 * {@code design-notes.md} → "Snapshotted reference data" for the
 * rationale + the named alternative.
 */
public class Customer {

    /**
     * Customer lifecycle status. Carries its wire-format string via
     * {@link #code()} (same shape as {@code ProductType}), so callers can
     * compare against {@code sales.customer.status} JDBC rows without a
     * separate String-constant set.
     */
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
            throw Assert.unknownValue("customer status", value);
        }
    }

    /**
     * Wire-format aggregate-type stamped onto {@code sales.outbox_message.aggregate_type}
     * for events this aggregate emits.
     */
    public static final String AGGREGATE_TYPE = SalesAggregateTypes.CUSTOMER;

    private final CustomerId id;
    private final String customerCode;  // never changes — identity field

    private String name;
    private String email;
    private String phone;
    private String billingAddress;
    private String shippingAddress;
    private Status status;
    /**
     * Default commercial {@link PaymentTerms} for this customer. Snapshotted
     * onto each sales order at placement time (overridable per order).
     * Customer's terms are set at registration. If post-registration changes
     * become a real demand, add {@code changeDefaultPaymentTerms} + a
     * {@code CustomerDefaultPaymentTermsChanged} event.
     */
    private final PaymentTerms defaultPaymentTerms;
    private final long version;

    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private Customer(
        CustomerId id, String customerCode, String name,
        String email, String phone,
        String billingAddress, String shippingAddress,
        Status status, PaymentTerms defaultPaymentTerms, long version
    ) {
        this.id = id;
        this.customerCode = customerCode;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.billingAddress = billingAddress;
        this.shippingAddress = shippingAddress;
        this.status = status;
        this.defaultPaymentTerms = defaultPaymentTerms;
        this.version = version;
    }

    /** Factory — minted aggregate. Emits {@link CustomerRegistered}. */
    public static Customer register(
        String customerCode, String name,
        String email, String phone,
        String billingAddress, String shippingAddress,
        PaymentTerms defaultPaymentTerms
    ) {
        Assert.notBlank(customerCode, "customerCode required");
        Assert.notBlank(name, "name required");
        Assert.notNull(defaultPaymentTerms, "defaultPaymentTerms required");

        CustomerId id = CustomerId.newId();
        Customer c = new Customer(
            id, customerCode, name,
            email, phone, billingAddress, shippingAddress,
            Status.ACTIVE, defaultPaymentTerms, 0L
        );
        c.pendingEvents.add(new CustomerRegistered(
            UUID.randomUUID(), id.value(),
            customerCode, name, email, phone,
            billingAddress, shippingAddress,
            Instant.now()
        ));
        return c;
    }

    /** Reconstitute from persistence. Emits nothing. */
    public static Customer reconstitute(
        CustomerId id, String customerCode, String name,
        String email, String phone,
        String billingAddress, String shippingAddress,
        Status status, PaymentTerms defaultPaymentTerms, long version
    ) {
        return new Customer(
            id, customerCode, name, email, phone,
            billingAddress, shippingAddress, status, defaultPaymentTerms, version
        );
    }

    /**
     * Rename. Snapshot-only consumers (see class Javadoc) — orders placed
     * before the change keep the old name forever. No-op suppression: same
     * name as current is silently ignored. Discontinued/blocked customers
     * cannot be renamed (operationally suspect).
     */
    public void changeName(String newName) {
        Assert.notBlank(newName, "newName required");
        Assert.state(status == Status.ACTIVE, "Cannot rename a non-active customer (status=" + status + ")");
        if (newName.equals(this.name)) return;
        String oldName = this.name;
        this.name = newName;
        pendingEvents.add(new CustomerNameChanged(
            UUID.randomUUID(), id.value(), oldName, newName, Instant.now()
        ));
    }

    /** Update email + phone. No-op suppression on identical values. */
    public void changeContact(String email, String phone) {
        Assert.state(status == Status.ACTIVE, "Cannot change contact on a non-active customer (status=" + status + ")");
        if (Objects.equals(this.email, email) && Objects.equals(this.phone, phone)) return;
        this.email = email;
        this.phone = phone;
        pendingEvents.add(new CustomerContactChanged(
            UUID.randomUUID(), id.value(), email, phone, Instant.now()
        ));
    }

    public void changeBillingAddress(String newAddress) {
        Assert.state(status == Status.ACTIVE, "Cannot change billing address on a non-active customer (status=" + status + ")");
        if (Objects.equals(this.billingAddress, newAddress)) return;
        String old = this.billingAddress;
        this.billingAddress = newAddress;
        pendingEvents.add(new CustomerAddressChanged(
            UUID.randomUUID(), id.value(), "billing", old, newAddress, Instant.now()
        ));
    }

    public void changeShippingAddress(String newAddress) {
        Assert.state(status == Status.ACTIVE, "Cannot change shipping address on a non-active customer (status=" + status + ")");
        if (Objects.equals(this.shippingAddress, newAddress)) return;
        String old = this.shippingAddress;
        this.shippingAddress = newAddress;
        pendingEvents.add(new CustomerAddressChanged(
            UUID.randomUUID(), id.value(), "shipping", old, newAddress, Instant.now()
        ));
    }

    /** Soft-delete. Idempotent — re-deactivating an inactive customer is a no-op. */
    public void deactivate(String reason) {
        if (status == Status.INACTIVE) return;
        Assert.state(status != Status.BLOCKED, "Blocked customers cannot be deactivated; use the unblock+deactivate path");
        this.status = Status.INACTIVE;
        pendingEvents.add(new CustomerDeactivated(
            UUID.randomUUID(), id.value(), reason, Instant.now()
        ));
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    public CustomerId id()             { return id; }
    public String customerCode()       { return customerCode; }
    public String name()               { return name; }
    public String email()              { return email; }
    public String phone()              { return phone; }
    public String billingAddress()     { return billingAddress; }
    public String shippingAddress()    { return shippingAddress; }
    public Status status()             { return status; }
    public PaymentTerms defaultPaymentTerms() { return defaultPaymentTerms; }
    public long version()              { return version; }
}
