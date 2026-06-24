package com.northwood.search.api;

import com.northwood.search.application.AuditSearchQueryPort;
import com.northwood.search.application.dto.AuditSearchResults;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit / event-analytics search (read model C): full-text + filtered search over
 * every projected event, newest-first, with per-service / per-type / per-aggregate
 * facets. The search-side successor to the BFF's in-memory audit fan-out; Kibana
 * charts the same {@code audit-events} index for time-series dashboards. Reached
 * through the BFF {@code /api/search} proxy route.
 */
@RestController
public class AuditSearchController {

    private final AuditSearchQueryPort auditSearch;

    public AuditSearchController(AuditSearchQueryPort auditSearch) {
        this.auditSearch = auditSearch;
    }

    @GetMapping("/api/search/audit")
    public AuditSearchResults searchAudit(
        @RequestParam(name = "q", required = false) String q,
        @RequestParam(name = "service", required = false) String service,
        @RequestParam(name = "eventType", required = false) String eventType,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        return auditSearch.search(q, service, eventType, page, size);
    }
}
