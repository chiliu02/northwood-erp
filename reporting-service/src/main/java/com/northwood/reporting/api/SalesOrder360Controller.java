package com.northwood.reporting.api;

import com.northwood.reporting.application.dto.SalesOrder360View;
import com.northwood.reporting.application.SalesOrder360QueryPort;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only HTTP front door for the order-360 projection. Returns 404 when
 * the row hasn't been seeded yet (typically: the order was placed but the
 * inbox handler hasn't drained the {@code sales.SalesOrderPlaced} event).
 */
@RestController
@RequestMapping("/api/sales-orders")
public class SalesOrder360Controller {

    private final SalesOrder360QueryPort port;

    public SalesOrder360Controller(SalesOrder360QueryPort port) {
        this.port = port;
    }

    @GetMapping("/{id}/360")
    public ResponseEntity<SalesOrder360View> get(@PathVariable UUID id) {
        return port.findBySalesOrderId(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** All projected orders, newest activity first. */
    @GetMapping
    public List<SalesOrder360View> list() {
        return port.findAll();
    }
}
