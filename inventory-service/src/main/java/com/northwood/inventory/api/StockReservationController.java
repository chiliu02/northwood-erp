package com.northwood.inventory.api;

import com.northwood.inventory.application.StockReservationQueryPort;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only list endpoint backing the ERP UI's stock-reservations screen.
 * Reservations are saga-driven (created by {@code StockReservationService}
 * in response to inbound {@code StockReservationRequested} events) — the
 * UI is observation-only.
 */
@RestController
@RequestMapping("/api/stock-reservations")
public class StockReservationController {

    private final StockReservationQueryPort query;

    public StockReservationController(StockReservationQueryPort query) {
        this.query = query;
    }

    @GetMapping
    public List<StockReservationQueryPort.ReservationRow> list() {
        return query.listAll();
    }
}
