package com.northwood.finance.api;

import com.northwood.finance.application.PaymentService;
import com.northwood.finance.application.dto.PaymentView;
import com.northwood.finance.application.dto.RecordCustomerPaymentCommand;
import com.northwood.finance.application.dto.RecordCustomerPaymentMultiCommand;
import com.northwood.finance.application.dto.RecordSupplierPaymentCommand;
import com.northwood.finance.application.dto.RecordSupplierPaymentMultiCommand;
import com.northwood.shared.api.security.RequireAccountant;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    /**
     * Outgoing supplier payment. Allocates against an approved supplier invoice
     * and (on full settlement) closes the P2P saga. Root path preserved for
     * backward-compat with the phase 5a smoke-test contract.
     */
    @PostMapping
    @RequireAccountant
    public ResponseEntity<PaymentView> recordSupplierPayment(
        @Valid @RequestBody RecordSupplierPaymentCommand command
    ) {
        return created(service.recordSupplierPayment(command));
    }

    /**
     * Incoming customer payment. Allocates against a posted customer invoice
     * and (on full settlement) closes the sales fulfilment saga.
     */
    @PostMapping("/customer")
    @RequireAccountant
    public ResponseEntity<PaymentView> recordCustomerPayment(
        @Valid @RequestBody RecordCustomerPaymentCommand command
    ) {
        return created(service.recordCustomerPayment(command));
    }

    /**
     * Multi-invoice supplier payment. One physical movement of cash settles
     * several approved supplier invoices from the same supplier (same currency).
     * Emits one {@code SupplierPaymentMade} per invoice so each P2P saga gets
     * routed correctly.
     */
    @PostMapping("/multi")
    @RequireAccountant
    public ResponseEntity<PaymentView> recordSupplierPaymentMulti(
        @Valid @RequestBody RecordSupplierPaymentMultiCommand command
    ) {
        return created(service.recordSupplierPaymentMulti(command));
    }

    /**
     * Multi-invoice customer payment. Mirror of {@link #recordSupplierPaymentMulti}
     * for incoming AR — one cheque/transfer covering several invoices.
     */
    @PostMapping("/customer/multi")
    @RequireAccountant
    public ResponseEntity<PaymentView> recordCustomerPaymentMulti(
        @Valid @RequestBody RecordCustomerPaymentMultiCommand command
    ) {
        return created(service.recordCustomerPaymentMulti(command));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentView> getById(@PathVariable UUID id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * All payments (AP + AR), newest first. Header-only — drilling into a
     * specific payment triggers findById which loads allocations.
     */
    @GetMapping
    public List<PaymentView> list() {
        return service.findAll();
    }

    private ResponseEntity<PaymentView> created(PaymentView view) {
        return ResponseEntity
            .created(URI.create("/api/payments/" + view.id()))
            .body(view);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<String> handleBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
