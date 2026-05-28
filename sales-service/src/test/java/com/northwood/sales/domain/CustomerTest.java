package com.northwood.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.sales.domain.events.CustomerAddressChanged;
import com.northwood.sales.domain.events.CustomerContactChanged;
import com.northwood.sales.domain.events.CustomerDeactivated;
import com.northwood.sales.domain.events.CustomerNameChanged;
import com.northwood.sales.domain.events.CustomerRegistered;
import com.northwood.shared.domain.DomainEvent;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CustomerTest {

    private static Customer activeCustomer() {
        Customer c = Customer.register(
            "CUST-001", "Acme Co",
            "ap@acme.example", "+61-2-1234-5678",
            "1 Acme St, Sydney", "1 Acme St, Sydney",
            PaymentTerms.ON_SHIPMENT
        );
        c.pullPendingEvents();   // drain CustomerRegistered
        return c;
    }

    private static Customer blockedCustomer() {
        return Customer.reconstitute(
            CustomerId.newId(), "CUST-002", "Blocked Co",
            null, null, null, null,
            Customer.Status.BLOCKED, PaymentTerms.ON_SHIPMENT, 1L
        );
    }

    private static Customer inactiveCustomer() {
        return Customer.reconstitute(
            CustomerId.newId(), "CUST-003", "Inactive Co",
            null, null, null, null,
            Customer.Status.INACTIVE, PaymentTerms.ON_SHIPMENT, 1L
        );
    }

    @Nested
    class Register {
        @Test void rejects_null_customer_code() {
            assertThatThrownBy(() -> Customer.register(null, "n", null, null, null, null, PaymentTerms.ON_SHIPMENT))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_blank_customer_code() {
            assertThatThrownBy(() -> Customer.register("", "n", null, null, null, null, PaymentTerms.ON_SHIPMENT))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_name() {
            assertThatThrownBy(() -> Customer.register("CUST-X", null, null, null, null, null, PaymentTerms.ON_SHIPMENT))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_blank_name() {
            assertThatThrownBy(() -> Customer.register("CUST-X", "  ", null, null, null, null, PaymentTerms.ON_SHIPMENT))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void allows_null_optional_fields() {
            Customer c = Customer.register("CUST-X", "X", null, null, null, null, PaymentTerms.ON_SHIPMENT);
            assertThat(c.email()).isNull();
            assertThat(c.phone()).isNull();
            assertThat(c.billingAddress()).isNull();
            assertThat(c.shippingAddress()).isNull();
        }

        @Test void starts_active() {
            Customer c = Customer.register("CUST-X", "X", null, null, null, null, PaymentTerms.ON_SHIPMENT);
            assertThat(c.status()).isEqualTo(Customer.Status.ACTIVE);
        }

        @Test void emits_CustomerRegistered_with_aggregate_identity() {
            Customer c = Customer.register("CUST-X", "Alpha", "a@x", "p", "b", "s", PaymentTerms.ON_SHIPMENT);
            List<DomainEvent> events = c.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(CustomerRegistered.class);
            CustomerRegistered e = (CustomerRegistered) events.get(0);
            assertThat(e.aggregateId()).isEqualTo(c.id().value());
            assertThat(e.customerCode()).isEqualTo("CUST-X");
            assertThat(e.name()).isEqualTo("Alpha");
        }
    }

    @Nested
    class ChangeName {
        @Test void rejects_null() {
            Customer c = activeCustomer();
            assertThatThrownBy(() -> c.changeName(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_blank() {
            Customer c = activeCustomer();
            assertThatThrownBy(() -> c.changeName("   "))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_when_inactive() {
            Customer c = inactiveCustomer();
            assertThatThrownBy(() -> c.changeName("New Name"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void rejects_when_blocked() {
            Customer c = blockedCustomer();
            assertThatThrownBy(() -> c.changeName("New Name"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void no_op_on_same_name_emits_nothing() {
            Customer c = activeCustomer();
            c.changeName("Acme Co");   // same as registered name
            assertThat(c.pullPendingEvents()).isEmpty();
        }

        @Test void emits_CustomerNameChanged_with_old_and_new() {
            Customer c = activeCustomer();
            c.changeName("Acme Holdings");
            assertThat(c.name()).isEqualTo("Acme Holdings");
            List<DomainEvent> events = c.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(CustomerNameChanged.class);
            CustomerNameChanged e = (CustomerNameChanged) events.get(0);
            assertThat(e.oldName()).isEqualTo("Acme Co");
            assertThat(e.newName()).isEqualTo("Acme Holdings");
        }
    }

    @Nested
    class ChangeContact {
        @Test void rejects_when_inactive() {
            Customer c = inactiveCustomer();
            assertThatThrownBy(() -> c.changeContact("new@x", "+61-3-9999-0000"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void no_op_on_unchanged_email_and_phone() {
            Customer c = activeCustomer();
            c.changeContact("ap@acme.example", "+61-2-1234-5678");
            assertThat(c.pullPendingEvents()).isEmpty();
        }

        @Test void emits_CustomerContactChanged() {
            Customer c = activeCustomer();
            c.changeContact("new@acme.example", "+61-3-9999-0000");
            assertThat(c.email()).isEqualTo("new@acme.example");
            assertThat(c.phone()).isEqualTo("+61-3-9999-0000");
            List<DomainEvent> events = c.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(CustomerContactChanged.class);
        }

        @Test void null_contact_is_allowed_when_changing_from_non_null() {
            Customer c = activeCustomer();
            c.changeContact(null, null);
            assertThat(c.email()).isNull();
            assertThat(c.phone()).isNull();
            assertThat(c.pullPendingEvents()).hasSize(1);
        }
    }

    @Nested
    class ChangeBillingAddress {
        @Test void rejects_when_inactive() {
            Customer c = inactiveCustomer();
            assertThatThrownBy(() -> c.changeBillingAddress("99 New St"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void no_op_on_unchanged() {
            Customer c = activeCustomer();
            c.changeBillingAddress("1 Acme St, Sydney");
            assertThat(c.pullPendingEvents()).isEmpty();
        }

        @Test void emits_CustomerAddressChanged_with_kind_billing() {
            Customer c = activeCustomer();
            c.changeBillingAddress("2 Acme Lane, Sydney");
            CustomerAddressChanged e = (CustomerAddressChanged) c.pullPendingEvents().get(0);
            assertThat(e.addressType()).isEqualTo("billing");
            assertThat(e.newAddress()).isEqualTo("2 Acme Lane, Sydney");
        }
    }

    @Nested
    class ChangeShippingAddress {
        @Test void rejects_when_inactive() {
            Customer c = inactiveCustomer();
            assertThatThrownBy(() -> c.changeShippingAddress("99 New St"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void no_op_on_unchanged() {
            Customer c = activeCustomer();
            c.changeShippingAddress("1 Acme St, Sydney");
            assertThat(c.pullPendingEvents()).isEmpty();
        }

        @Test void emits_CustomerAddressChanged_with_kind_shipping() {
            Customer c = activeCustomer();
            c.changeShippingAddress("3 Acme Crescent, Sydney");
            CustomerAddressChanged e = (CustomerAddressChanged) c.pullPendingEvents().get(0);
            assertThat(e.addressType()).isEqualTo("shipping");
            assertThat(e.newAddress()).isEqualTo("3 Acme Crescent, Sydney");
        }
    }

    @Nested
    class Deactivate {
        @Test void flips_status_to_inactive_and_emits_event() {
            Customer c = activeCustomer();
            c.deactivate("end-of-life");
            assertThat(c.status()).isEqualTo(Customer.Status.INACTIVE);
            CustomerDeactivated e = (CustomerDeactivated) c.pullPendingEvents().get(0);
            assertThat(e.reason()).isEqualTo("end-of-life");
        }

        @Test void idempotent_on_already_inactive() {
            Customer c = inactiveCustomer();
            c.deactivate("again");
            assertThat(c.pullPendingEvents()).isEmpty();
        }

        @Test void rejects_blocked_to_inactive_path() {
            Customer c = blockedCustomer();
            assertThatThrownBy(() -> c.deactivate("just because"))
                .isInstanceOf(IllegalStateException.class);
        }
    }
}
