package com.northwood.search.api;

import com.northwood.search.application.GlobalSearchQueryPort;
import com.northwood.search.application.dto.GlobalSearchResults;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Global cross-service search (read model B): one box over every bounded
 * context. {@code GET /api/search?q=acme} returns matching customers, orders,
 * suppliers, purchase orders, invoices and products in one relevance-ranked
 * list, with per-type facet counts. Reached through the BFF {@code /api/search}
 * proxy route.
 *
 * <p>Distinct from {@code /api/search/products} (read model A, the richer
 * catalog search) — this is the one-box-everything entry point.
 */
@RestController
public class GlobalSearchController {

    private final GlobalSearchQueryPort globalSearch;

    public GlobalSearchController(GlobalSearchQueryPort globalSearch) {
        this.globalSearch = globalSearch;
    }

    @GetMapping("/api/search")
    public GlobalSearchResults search(
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "type", required = false) String entityType,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        return globalSearch.search(q, entityType, page, size);
    }
}
