package com.northwood.sales.api;

import com.northwood.sales.api.dto.AddOrderLineRequest;
import com.northwood.sales.api.dto.CancelOrderRequest;
import com.northwood.sales.api.dto.ChangeOrderLineQuantityRequest;
import com.northwood.sales.api.dto.ChangeOrderLineUnitPriceRequest;
import com.northwood.sales.application.SalesOrderService;
import com.northwood.sales.application.dto.AddOrderLineCommand;
import com.northwood.sales.application.dto.CancelOrderCommand;
import com.northwood.sales.application.dto.ChangeOrderLineQuantityCommand;
import com.northwood.sales.application.dto.ChangeOrderLineUnitPriceCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.RemoveOrderLineCommand;
import com.northwood.sales.application.dto.SalesOrderView;
import com.northwood.shared.api.security.RequireSalesClerk;
import com.northwood.shared.api.security.RequireSalesManager;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    // ------------------------------------------------------------
    // Line amendment (post-placement, pre-reservation — this slice).
    // Optimistic concurrency: callers pass the order version they last saw in
    // the If-Match header; a stale value is rejected (409) before any mutation.
    // Each response carries the new version as the ETag.
    // ------------------------------------------------------------

    @PostMapping("/{id}/lines")
    @RequireSalesClerk
    public ResponseEntity<SalesOrderView> addLine(
        @PathVariable UUID id,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @Valid @RequestBody AddOrderLineRequest request
    ) {
        SalesOrderView view = service.addLine(new AddOrderLineCommand(
            id, parseIfMatch(ifMatch),
            request.productId(), request.productSku(), request.productName(),
            request.orderedQuantity(), request.unitPrice(), request.taxRate()
        ));
        return withETag(view);
    }

    @PatchMapping("/{id}/lines/{lineId}")
    @RequireSalesClerk
    public ResponseEntity<SalesOrderView> changeLineQuantity(
        @PathVariable UUID id,
        @PathVariable UUID lineId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @Valid @RequestBody ChangeOrderLineQuantityRequest request
    ) {
        SalesOrderView view = service.changeLineQuantity(new ChangeOrderLineQuantityCommand(
            id, lineId, parseIfMatch(ifMatch), request.orderedQuantity()
        ));
        return withETag(view);
    }

    @PatchMapping("/{id}/lines/{lineId}/price")
    @RequireSalesManager
    public ResponseEntity<SalesOrderView> changeLineUnitPrice(
        @PathVariable UUID id,
        @PathVariable UUID lineId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @Valid @RequestBody ChangeOrderLineUnitPriceRequest request
    ) {
        SalesOrderView view = service.changeLineUnitPrice(new ChangeOrderLineUnitPriceCommand(
            id, lineId, parseIfMatch(ifMatch), request.unitPrice()
        ));
        return withETag(view);
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    @RequireSalesClerk
    public ResponseEntity<SalesOrderView> removeLine(
        @PathVariable UUID id,
        @PathVariable UUID lineId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch
    ) {
        SalesOrderView view = service.removeLine(new RemoveOrderLineCommand(
            id, lineId, parseIfMatch(ifMatch)
        ));
        return withETag(view);
    }

    private static ResponseEntity<SalesOrderView> withETag(SalesOrderView view) {
        return ResponseEntity.ok().eTag("\"" + view.version() + "\"").body(view);
    }

    /**
     * Parse an {@code If-Match} value to the order version, tolerating the
     * (weak-)ETag quoting forms a client might send ({@code "3"}, {@code W/"3"},
     * or a bare {@code 3}). Absent or unparseable → null (no staleness check;
     * the in-transaction version guard still protects against lost updates).
     */
    private static Long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String trimmed = ifMatch.trim();
        if (trimmed.startsWith("W/")) {
            trimmed = trimmed.substring(2);
        }
        trimmed = trimmed.replace("\"", "").trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
