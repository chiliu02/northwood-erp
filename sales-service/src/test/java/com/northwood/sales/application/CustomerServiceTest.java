package com.northwood.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.sales.application.dto.CustomerView;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.CustomerId;
import com.northwood.sales.domain.PaymentTerms;
import com.northwood.sales.domain.CustomerRepository;
import com.northwood.sales.domain.events.CustomerAddressChanged;
import com.northwood.sales.domain.events.CustomerContactChanged;
import com.northwood.sales.domain.events.CustomerDeactivated;
import com.northwood.sales.domain.events.CustomerNameChanged;
import com.northwood.sales.domain.events.CustomerRegistered;
import com.northwood.shared.domain.DomainEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CustomerService}'s six @Transactional commands.
 * Pattern matches the rest of the application-layer service tests:
 * Mockito mocks for the repository, real {@link Customer} aggregate
 * fixtures, capture saved aggregate's pending events for assertions.
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    private static final UUID CID = UUID.randomUUID();

    @Mock CustomerRepository repo;

    private CustomerService service;

    @BeforeEach
    void setUp() {
        service = new CustomerService(repo);
    }

    private Customer activeCustomer() {
        return Customer.reconstitute(
            CustomerId.of(CID), "CUST-001", "Acme Corp",
            "ops@acme.example", "+61-2-9000-0000",
            "1 Acme St, Sydney", "1 Acme St, Sydney",
            Customer.Status.ACTIVE, PaymentTerms.ON_SHIPMENT, 1L
        );
    }

    private List<DomainEvent> savedEvents() {
        ArgumentCaptor<Customer> cap = ArgumentCaptor.forClass(Customer.class);
        verify(repo).save(cap.capture());
        return cap.getValue().pullPendingEvents();
    }

    @Nested class RegisterCustomer {

        @Test void registers_active_customer_and_emits_registered_event() {
            CustomerView registered = service.registerCustomer(
                "CUST-002", "Beta Industries",
                "ap@beta.example", "+61-3-8000-0000",
                "10 Beta Ave, Melbourne", "10 Beta Ave, Melbourne",
                PaymentTerms.ON_SHIPMENT.code()
            );
            assertThat(registered).isNotNull();
            assertThat(registered.customerId()).isNotNull();

            List<DomainEvent> events = savedEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(CustomerRegistered.class);
            CustomerRegistered ev = (CustomerRegistered) events.get(0);
            assertThat(ev.customerCode()).isEqualTo("CUST-002");
            assertThat(ev.name()).isEqualTo("Beta Industries");
        }

        @Test void rejects_blank_customer_code() {
            assertThatThrownBy(() -> service.registerCustomer(
                "", "name", null, null, null, null, PaymentTerms.ON_SHIPMENT.code()
            )).isInstanceOf(IllegalArgumentException.class);

            verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested class ChangeName {

        @Test void renames_active_customer_and_emits_event() {
            Customer c = activeCustomer();
            when(repo.findById(CustomerId.of(CID))).thenReturn(Optional.of(c));

            service.changeName(CID, "Acme Holdings");

            assertThat(c.name()).isEqualTo("Acme Holdings");
            List<DomainEvent> events = savedEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(CustomerNameChanged.class);
        }

        @Test void no_op_on_unchanged_name_emits_nothing() {
            Customer c = activeCustomer();
            when(repo.findById(CustomerId.of(CID))).thenReturn(Optional.of(c));

            service.changeName(CID, "Acme Corp");

            // The aggregate's no-op suppression silently ignores; service still saves
            // (defensive — caller called the method, repository.save is idempotent).
            assertThat(c.pullPendingEvents()).isEmpty();
        }

        @Test void rejects_when_customer_not_found() {
            when(repo.findById(CustomerId.of(CID))).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.changeName(CID, "Whatever"))
                .isInstanceOf(CustomerService.CustomerNotFoundException.class)
                .hasMessageContaining(CID.toString());
            verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested class ChangeContact {

        @Test void updates_email_and_phone_and_emits_event() {
            Customer c = activeCustomer();
            when(repo.findById(CustomerId.of(CID))).thenReturn(Optional.of(c));

            service.changeContact(CID, "new@acme.example", "+61-2-9000-9999");

            List<DomainEvent> events = savedEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(CustomerContactChanged.class);
            CustomerContactChanged ev = (CustomerContactChanged) events.get(0);
            assertThat(ev.email()).isEqualTo("new@acme.example");
            assertThat(ev.phone()).isEqualTo("+61-2-9000-9999");
        }

        @Test void no_op_on_unchanged_contact() {
            Customer c = activeCustomer();
            when(repo.findById(CustomerId.of(CID))).thenReturn(Optional.of(c));

            service.changeContact(CID, "ops@acme.example", "+61-2-9000-0000");

            assertThat(c.pullPendingEvents()).isEmpty();
        }
    }

    @Nested class ChangeBillingAddress {

        @Test void updates_billing_address_and_emits_event() {
            Customer c = activeCustomer();
            when(repo.findById(CustomerId.of(CID))).thenReturn(Optional.of(c));

            service.changeBillingAddress(CID, "100 New St, Sydney");

            List<DomainEvent> events = savedEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(CustomerAddressChanged.class);
            CustomerAddressChanged ev = (CustomerAddressChanged) events.get(0);
            assertThat(ev.addressType()).isEqualTo("billing");
            assertThat(ev.newAddress()).isEqualTo("100 New St, Sydney");
        }
    }

    @Nested class ChangeShippingAddress {

        @Test void updates_shipping_address_and_emits_event() {
            Customer c = activeCustomer();
            when(repo.findById(CustomerId.of(CID))).thenReturn(Optional.of(c));

            service.changeShippingAddress(CID, "200 Ship Rd, Sydney");

            List<DomainEvent> events = savedEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(CustomerAddressChanged.class);
            CustomerAddressChanged ev = (CustomerAddressChanged) events.get(0);
            assertThat(ev.addressType()).isEqualTo("shipping");
            assertThat(ev.newAddress()).isEqualTo("200 Ship Rd, Sydney");
        }
    }

    @Nested class Deactivate {

        @Test void deactivates_active_customer_and_emits_event() {
            Customer c = activeCustomer();
            when(repo.findById(CustomerId.of(CID))).thenReturn(Optional.of(c));

            service.deactivate(CID, "no longer trading");

            assertThat(c.status()).isEqualTo(Customer.Status.INACTIVE);
            List<DomainEvent> events = savedEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(CustomerDeactivated.class);
            CustomerDeactivated ev = (CustomerDeactivated) events.get(0);
            assertThat(ev.reason()).isEqualTo("no longer trading");
        }

        @Test void deactivating_already_inactive_is_idempotent() {
            Customer c = Customer.reconstitute(
                CustomerId.of(CID), "CUST-001", "Acme Corp",
                null, null, null, null,
                Customer.Status.INACTIVE, PaymentTerms.ON_SHIPMENT, 2L
            );
            when(repo.findById(CustomerId.of(CID))).thenReturn(Optional.of(c));

            service.deactivate(CID, "again");

            // Idempotent — aggregate emits no event.
            assertThat(c.pullPendingEvents()).isEmpty();
            verify(repo, times(1)).save(c);
        }
    }
}
