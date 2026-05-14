package com.northwood.reporting.api;

import com.northwood.reporting.application.dto.MaterialShortageView;
import com.northwood.reporting.application.MaterialShortageQueryPort;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/material-shortages")
public class MaterialShortageController {

    private final MaterialShortageQueryPort port;

    public MaterialShortageController(MaterialShortageQueryPort port) {
        this.port = port;
    }

    /**
     * List shortages. {@code includeResolved=true} returns the full history;
     * default is the active list (status ≠ 'resolved'), which is what a
     * "what's currently short?" dashboard wants.
     */
    @GetMapping
    public List<MaterialShortageView> list(
        @RequestParam(name = "includeResolved", defaultValue = "false") boolean includeResolved
    ) {
        var rows = includeResolved ? port.findAll() : port.findActive();
        return rows;
    }

    @GetMapping("/{productId}")
    public ResponseEntity<MaterialShortageView> get(@PathVariable UUID productId) {
        return port.findByProductId(productId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
