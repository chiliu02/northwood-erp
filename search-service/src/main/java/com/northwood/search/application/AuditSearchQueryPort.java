package com.northwood.search.application;

import com.northwood.search.application.dto.AuditSearchResults;

/**
 * Read side of the {@code audit-events} index (read model C) — full-text +
 * filtered audit search with per-service / per-type facets, newest-first. The
 * search-side successor to the BFF's in-memory audit fan-out; Kibana dashboards
 * over the same index for time-series analytics.
 */
public interface AuditSearchQueryPort {

    /**
     * @param q         free-text over the event payload; blank → match all
     * @param service   optional {@code sourceService} filter; null → all services
     * @param eventType optional {@code eventType} filter; null → all types
     */
    AuditSearchResults search(String q, String service, String eventType, int page, int size);
}
