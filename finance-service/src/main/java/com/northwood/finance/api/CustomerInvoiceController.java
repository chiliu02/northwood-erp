package com.northwood.finance.api;

import com.northwood.finance.application.CustomerInvoiceService;
import com.northwood.finance.application.dto.CustomerInvoiceView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only HTTP front for customer invoices. Customer invoices are
 * created automatically by {@code SalesOrderShippedHandler} from a
 * shipment event — no creation endpoint here. The list + by-id reads
 * support the operational UI's customer-invoice screens.
 */
@RestController
@RequestMapping("/api/customer-invoices")
public class CustomerInvoiceController {

    private final CustomerInvoiceService service;

    public CustomerInvoiceController(CustomerInvoiceService service) {
        this.service = service;
    }

    @GetMapping
    public List<CustomerInvoiceView> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerInvoiceView> getById(@PathVariable UUID id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
