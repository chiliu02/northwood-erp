package com.northwood.sales.application;

import com.northwood.sales.application.dto.CustomerView;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.CustomerId;
import com.northwood.sales.domain.CustomerRepository;
import com.northwood.sales.domain.PaymentTerms;
import com.northwood.shared.application.exception.ConflictException;
import com.northwood.shared.application.exception.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the {@link Customer} aggregate. Thin pass-through
 * to the aggregate's intent-named methods. No-op suppression lives on the
 * aggregate itself (consistent with the no-op-on-aggregate pattern across the codebase).
 */
@Service
public class CustomerService {

    public static class CustomerNotFoundException extends NotFoundException {
        public static final String CODE = "CUSTOMER_NOT_FOUND";
        private final UUID customerId;
        public CustomerNotFoundException(UUID id) {
            super(CODE, "Customer not found: " + id);
            this.customerId = id;
        }
        public UUID customerId() { return customerId; }
        @Override public Map<String, Object> params() { return Map.of("customerId", customerId); }
    }

    /**
     * Thrown by {@link CustomerRepository#save(Customer)} when {@code customer_code}
     * conflicts with an existing row. Lives on the application service rather
     * than on the JDBC adapter so the controller (and other callers) can
     * reference it without reaching into infrastructure.
     */
    public static class DuplicateCustomerCodeException extends ConflictException {
        public static final String CODE = "DUPLICATE_CUSTOMER_CODE";
        private final String customerCode;
        public DuplicateCustomerCodeException(String code, Throwable cause) {
            super(CODE, "customer_code already exists: " + code, cause);
            this.customerCode = code;
        }
        public String customerCode() { return customerCode; }
        @Override public Map<String, Object> params() { return Map.of("customerCode", customerCode); }
    }

    private final CustomerRepository customers;

    public CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    @Transactional
    public CustomerView registerCustomer(
        String customerCode, String name,
        String email, String phone,
        String billingAddress, String shippingAddress,
        String defaultPaymentTerms
    ) {
        // Parse the wire-format term here so the controller (api/) stays free of
        // the domain enum — mirrors product-service's ProductService.
        PaymentTerms terms = defaultPaymentTerms == null
            ? PaymentTerms.ON_SHIPMENT
            : PaymentTerms.fromDb(defaultPaymentTerms);
        Customer customer = Customer.register(
            customerCode, name, email, phone, billingAddress, shippingAddress, terms
        );
        customers.save(customer);
        return CustomerView.from(customer);
    }

    @Transactional(readOnly = true)
    public Optional<CustomerView> findById(UUID customerId) {
        return customers.findById(CustomerId.of(customerId)).map(CustomerView::from);
    }

    @Transactional(readOnly = true)
    public List<CustomerView> findAll() {
        return customers.findAll().stream().map(CustomerView::from).toList();
    }

    @Transactional
    public void changeName(UUID customerId, String newName) {
        Customer customer = load(customerId);
        customer.changeName(newName);
        customers.save(customer);
    }

    @Transactional
    public void changeContact(UUID customerId, String email, String phone) {
        Customer customer = load(customerId);
        customer.changeContact(email, phone);
        customers.save(customer);
    }

    @Transactional
    public void changeBillingAddress(UUID customerId, String newAddress) {
        Customer customer = load(customerId);
        customer.changeBillingAddress(newAddress);
        customers.save(customer);
    }

    @Transactional
    public void changeShippingAddress(UUID customerId, String newAddress) {
        Customer customer = load(customerId);
        customer.changeShippingAddress(newAddress);
        customers.save(customer);
    }

    @Transactional
    public void deactivate(UUID customerId, String reason) {
        Customer customer = load(customerId);
        customer.deactivate(reason);
        customers.save(customer);
    }

    private Customer load(UUID customerId) {
        return customers.findById(CustomerId.of(customerId))
            .orElseThrow(() -> new CustomerNotFoundException(customerId));
    }

}
