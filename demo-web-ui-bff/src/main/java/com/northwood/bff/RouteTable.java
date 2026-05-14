package com.northwood.bff;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Path-prefix → service-name routing table. Mirrors the dev proxy that lived
 * in {@code demo-web-ui/vite.config.ts} before the BFF; once the BFF is in
 * place the SPA's dev proxy collapses to a single {@code /api/*} → {@code 8080}.
 *
 * <p>Order matters: more-specific prefixes must come before less-specific
 * ones so {@code /api/sales-cmd} matches before {@code /api/sales-orders}.
 */
@Component
public class RouteTable {

    /** Path-rewrite-aware route entry. */
    public record Route(String prefix, String target, String rewrite) {}

    private final List<Route> routes = List.of(
        // /api/sagas (list + stream) is handled in-process by
        // SagaAggregatorController, not here. The per-service saga endpoints
        // remain reachable for debugging by hitting their service ports
        // directly (8082/8084/8085); they aren't proxied because SSE doesn't
        // work through the buffered HTTP proxy this BFF uses.

        // Aliased command paths (write goes to the owning service, read goes
        // to reporting on the same /api/sales-orders / /api/work-orders /
        // /api/purchase-orders). The same alias also handles owning-service
        // GETs that need the aggregate detail (header + lines), since the
        // reporting endpoint returns a denormalised projection that doesn't
        // include the line UUIDs the operational forms need to populate from.
        new Route("/api/sales-cmd",           "sales",         "/api"),
        new Route("/api/work-orders-cmd",     "manufacturing", "/api/work-orders"),
        new Route("/api/purchase-orders-cmd", "purchasing",    "/api/purchase-orders"),

        // Reporting reads
        new Route("/api/financial-dashboard", "reporting",     null),
        new Route("/api/sales-orders",        "reporting",     null),
        new Route("/api/purchase-orders",     "reporting",     null),
        new Route("/api/work-orders",         "reporting",     null),
        new Route("/api/material-shortages",  "reporting",     null),
        new Route("/api/atp",                 "reporting",     null),

        // Owning-service catalogs
        new Route("/api/products",            "product",       null),
        new Route("/api/stock-items",         "inventory",     null),
        new Route("/api/suppliers",           "purchasing",    null),
        new Route("/api/boms",                "manufacturing", null),

        // Inventory writes
        new Route("/api/goods-receipts",      "inventory",     null),
        new Route("/api/shipments",           "inventory",     null),

        // Finance writes + reads
        new Route("/api/customer-invoices",   "finance",       null),
        new Route("/api/supplier-invoices",   "finance",       null),
        new Route("/api/payments",            "finance",       null),
        new Route("/api/journal-entries",     "finance",       null),
        new Route("/api/exchange-rate",       "finance",       null),

        // Purchasing writes + reads
        new Route("/api/purchase-requisitions",   "purchasing", null),
        new Route("/api/supplier-product-prices", "purchasing", null)
    );

    public Route find(String path) {
        for (Route r : routes) {
            if (path.startsWith(r.prefix)) {
                return r;
            }
        }
        return null;
    }

    public List<Route> all() { return routes; }
}
