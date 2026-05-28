package com.northwood.sales.api;

import com.northwood.sales.api.dto.ChangeCustomerAddressRequest;
import com.northwood.sales.api.dto.ChangeCustomerContactRequest;
import com.northwood.sales.api.dto.ChangeCustomerNameRequest;
import com.northwood.sales.api.dto.DeactivateCustomerRequest;
import com.northwood.sales.api.dto.RegisterCustomerRequest;
import com.northwood.sales.application.CustomerService;
import com.northwood.sales.application.dto.CustomerView;
import com.northwood.sales.domain.PaymentTerms;
import com.northwood.shared.api.security.RequireSalesClerk;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @PostMapping
    @RequireSalesClerk
    public ResponseEntity<CustomerView> register(@Valid @RequestBody RegisterCustomerRequest request) {
        CustomerView view = service.registerCustomer(
            request.customerCode(), request.name(),
            request.email(), request.phone(),
            request.billingAddress(), request.shippingAddress(),
            request.defaultPaymentTerms() == null
                ? null
                : PaymentTerms.fromDb(request.defaultPaymentTerms())
        );
        return ResponseEntity
            .created(URI.create("/api/customers/" + view.customerId()))
            .body(view);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerView> getById(@PathVariable UUID id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<CustomerView> list() {
        return service.findAll();
    }

    @PutMapping("/{id}/name")
    @RequireSalesClerk
    public ResponseEntity<Void> changeName(
        @PathVariable UUID id,
        @Valid @RequestBody ChangeCustomerNameRequest request
    ) {
        service.changeName(id, request.name());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/contact")
    @RequireSalesClerk
    public ResponseEntity<Void> changeContact(
        @PathVariable UUID id,
        @Valid @RequestBody ChangeCustomerContactRequest request
    ) {
        service.changeContact(id, request.email(), request.phone());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/billing-address")
    @RequireSalesClerk
    public ResponseEntity<Void> changeBillingAddress(
        @PathVariable UUID id,
        @Valid @RequestBody ChangeCustomerAddressRequest request
    ) {
        service.changeBillingAddress(id, request.address());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/shipping-address")
    @RequireSalesClerk
    public ResponseEntity<Void> changeShippingAddress(
        @PathVariable UUID id,
        @Valid @RequestBody ChangeCustomerAddressRequest request
    ) {
        service.changeShippingAddress(id, request.address());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/deactivate")
    @RequireSalesClerk
    public ResponseEntity<Void> deactivate(
        @PathVariable UUID id,
        @Valid @RequestBody DeactivateCustomerRequest request
    ) {
        service.deactivate(id, request.reason());
        return ResponseEntity.noContent().build();
    }

}
