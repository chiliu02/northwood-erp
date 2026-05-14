package com.northwood.sales.api;

import com.northwood.sales.api.dto.CancelOrderRequest;
import com.northwood.sales.application.SalesOrderService;
import com.northwood.sales.application.SalesOrderService.CurrencyMismatchException;
import com.northwood.sales.application.SalesOrderService.CustomerInactiveException;
import com.northwood.sales.application.SalesOrderService.CustomerNotFoundException;
import com.northwood.sales.application.SalesOrderService.OrderNotCancellableException;
import com.northwood.sales.application.SalesOrderService.OrderNotFoundException;
import com.northwood.sales.application.SalesOrderService.ProductDiscontinuedException;
import com.northwood.sales.application.SalesOrderService.SagaNotFoundException;
import com.northwood.sales.application.SalesOrderService.UnknownPriceException;
import com.northwood.sales.application.dto.CancelOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.SalesOrderView;
import com.northwood.shared.api.security.RequireSalesClerk;
import com.northwood.shared.api.security.RequireSalesManager;
import jakarta.validation.Valid;
import java.net.URI;
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
@RequestMapping("/api/sales-orders")
public class SalesOrderController {

    private final SalesOrderService service;

    public SalesOrderController(SalesOrderService service) {
        this.service = service;
    }

    @PostMapping
    @RequireSalesClerk
    public ResponseEntity<SalesOrderView> place(@Valid @RequestBody PlaceOrderCommand command) {
        SalesOrderView view = service.placeOrder(command);
        return ResponseEntity
            .created(URI.create("/api/sales-orders/" + view.id()))
            .body(view);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SalesOrderView> getById(@PathVariable UUID id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/cancel")
    @RequireSalesManager
    public ResponseEntity<SalesOrderView> cancel(
        @PathVariable UUID id,
        @Valid @RequestBody CancelOrderRequest request
    ) {
        service.cancel(new CancelOrderCommand(id, request.reason()));
        SalesOrderView body = service.findById(id).orElseThrow();
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    public ResponseEntity<String> handleCustomerNotFound(CustomerNotFoundException e) {
        return ResponseEntity.status(404).body(e.getMessage());
    }

    @ExceptionHandler({OrderNotFoundException.class, SagaNotFoundException.class})
    public ResponseEntity<String> handleNotFound(RuntimeException e) {
        return ResponseEntity.status(404).body(e.getMessage());
    }

    @ExceptionHandler({OrderNotCancellableException.class, CustomerInactiveException.class, ProductDiscontinuedException.class})
    public ResponseEntity<String> handleConflict(RuntimeException e) {
        return ResponseEntity.status(409).body(e.getMessage());
    }

    @ExceptionHandler({UnknownPriceException.class, CurrencyMismatchException.class})
    public ResponseEntity<String> handleBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
