package com.northwood.erpbff;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Path-prefix → service-name routing table for the ERP BFF.
 *
 * <p>Order matters: more-specific prefixes must come before less-specific
 * ones (e.g. {@code /api/sales-cmd} before {@code /api/sales-orders}).
 *
 * <p>Convention (mirrors {@code web-ui-bff}): GET requests for projections
 * go to {@code reporting}; writes go via aliased {@code -cmd} prefixes to
 * the owning service. The alias keeps the route table simple — without it,
 * {@code POST /api/sales-orders} (sales) and {@code GET /api/sales-orders}
 * (reporting) would collide on the same prefix.
 */
@Component
public class RouteTable {

    /** Path-rewrite-aware route entry. */
    public record Route(String prefix, String target, String rewrite) {}

    private final List<Route> routes = List.of(
        // -------- Aliased command paths (writes to owning service) --------
        new Route("/api/sales-cmd",            "sales",         "/api"),
        new Route("/api/work-orders-cmd",      "manufacturing", "/api/work-orders"),
        new Route("/api/purchase-orders-cmd",  "purchasing",    "/api/purchase-orders"),
        new Route("/api/products-cmd",         "product",       "/api/products"),

        // -------- Reporting reads (projections) --------
        new Route("/api/financial-dashboard",  "reporting",     null),
        new Route("/api/sales-orders",         "reporting",     null),
        new Route("/api/purchase-orders",      "reporting",     null),
        new Route("/api/work-orders",          "reporting",     null),
        new Route("/api/material-shortages",   "reporting",     null),
        new Route("/api/atp",                  "reporting",     null),

        // -------- Owning-service catalogs --------
        new Route("/api/products",             "product",       null),
        new Route("/api/stock-items",          "inventory",     null),
        new Route("/api/customers",            "sales",         null),
        new Route("/api/suppliers",            "purchasing",    null),

        // -------- Inventory writes + reads --------
        new Route("/api/goods-receipts",       "inventory",     null),
        new Route("/api/shipments",            "inventory",     null),
        new Route("/api/stock-movements",      "inventory",     null),
        new Route("/api/stock-reservations",   "inventory",     null),

        // -------- Manufacturing reads --------
        new Route("/api/boms",                 "manufacturing", null),

        // -------- Purchasing writes --------
        new Route("/api/purchase-requisitions", "purchasing",   null),
        new Route("/api/supplier-product-prices", "purchasing", null),

        // -------- Finance writes + reads --------
        // /api/supplier-invoices/pending-review and /{id}/manual-approve / reject
        // are all under /api/supplier-invoices which routes to finance.
        new Route("/api/supplier-invoices",    "finance",       null),
        new Route("/api/customer-invoices",    "finance",       null),
        new Route("/api/payments",             "finance",       null),
        new Route("/api/journal-entries",      "finance",       null),
        new Route("/api/exchange-rate",        "finance",       null)
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
