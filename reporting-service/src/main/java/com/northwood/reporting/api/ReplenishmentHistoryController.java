package com.northwood.reporting.api;

import com.northwood.reporting.application.ReplenishmentHistoryQueryPort;
import com.northwood.reporting.application.dto.ReplenishmentHistoryView;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the replenishment history view. Powers
 * the "Replenishment activity" widget on the stock-items page in both
 * SPAs (demo + erp).
 *
 * <ul>
 *   <li>{@code GET /api/replenishment-history} — most recent N system-wide.</li>
 *   <li>{@code GET /api/replenishment-history?productId=...} — most recent N for one SKU.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/replenishment-history")
public class ReplenishmentHistoryController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final ReplenishmentHistoryQueryPort port;

    public ReplenishmentHistoryController(ReplenishmentHistoryQueryPort port) {
        this.port = port;
    }

    @GetMapping
    public List<ReplenishmentHistoryView> list(
        @RequestParam(required = false) UUID productId,
        @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit
    ) {
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return productId == null
            ? port.findAll(effectiveLimit)
            : port.findRecentForProduct(productId, effectiveLimit);
    }
}
