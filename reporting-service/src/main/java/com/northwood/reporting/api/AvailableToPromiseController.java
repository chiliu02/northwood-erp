package com.northwood.reporting.api;

import com.northwood.reporting.application.dto.AvailableToPromiseView;
import com.northwood.reporting.application.AvailableToPromiseQueryPort;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/atp")
public class AvailableToPromiseController {

    private final AvailableToPromiseQueryPort port;

    public AvailableToPromiseController(AvailableToPromiseQueryPort port) {
        this.port = port;
    }

    @GetMapping
    public List<AvailableToPromiseView> list() {
        return port.findAll();
    }

    @GetMapping("/{productId}")
    public ResponseEntity<AvailableToPromiseView> get(@PathVariable UUID productId) {
        return port.findByProductId(productId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
