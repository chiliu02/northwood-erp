package com.northwood.shared.api.audit;

import com.northwood.shared.application.audit.AuditEntry;
import com.northwood.shared.application.audit.AuditQueryPort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit-log endpoint backed by the local
 * {@code outbox_message} table. Every service exposes
 * {@code GET /api/audit?aggregateId=&from=&to=&limit=} with the same shape
 * since the controller lives in the shared module and is auto-configured
 * per service via {@link AuditAutoConfiguration}.
 *
 * <p>Authorization: any authenticated user. Auditor role works fine here —
 * the role table in the security plan reserves audit reads as
 * "any authenticated (incl. auditor)".
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;

    private final AuditQueryPort audit;

    public AuditController(AuditQueryPort audit) {
        this.audit = audit;
    }

    @GetMapping
    public List<AuditEntry> find(
        @RequestParam(required = false) UUID aggregateId,
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to,
        @RequestParam(required = false) Integer limit
    ) {
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return audit.find(aggregateId, from, to, effectiveLimit);
    }
}
