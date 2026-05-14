package com.northwood.inventory.api;

import com.northwood.inventory.application.StockMovementQueryPort;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only audit list of stock movements. The append-only
 * {@code stock_movement} table is written by {@code StockMovementWriter}
 * from each on-hand mutation site (receipt, shipment, top-level WO
 * completion). The UI shows the most recent {@code limit} rows.
 */
@RestController
@RequestMapping("/api/stock-movements")
public class StockMovementController {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;

    private final StockMovementQueryPort query;

    public StockMovementController(StockMovementQueryPort query) {
        this.query = query;
    }

    @GetMapping
    public List<StockMovementQueryPort.MovementRow> list(
        @RequestParam(required = false) Integer limit
    ) {
        int effective = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(1, limit), MAX_LIMIT);
        return query.listRecent(effective);
    }
}
