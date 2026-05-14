package com.northwood.reporting.api;

import com.northwood.reporting.application.dto.ProductionPlanningView;
import com.northwood.reporting.application.ProductionPlanningQueryPort;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/work-orders")
public class ProductionPlanningController {

    private final ProductionPlanningQueryPort port;

    public ProductionPlanningController(ProductionPlanningQueryPort port) {
        this.port = port;
    }

    @GetMapping("/{id}/board")
    public ResponseEntity<ProductionPlanningView> get(@PathVariable UUID id) {
        return port.findByWorkOrderId(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** All open work orders, newest activity first. */
    @GetMapping
    public List<ProductionPlanningView> list() {
        return port.findAll();
    }
}
