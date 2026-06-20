package com.northwood.loadtest;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.asLongAsDuring;
import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.listFeeder;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * REST execution of the concurrent load test ({@code docs/concurrent-load-test.md}
 * §7) — the <strong>stress</strong> run. Each virtual user is a distinct Keycloak
 * identity that drives one full order-to-cash flow against the LIVE stack:
 *
 * <pre>
 *   place order (sales_clerk) → poll until reserved → ship (warehouse_clerk)
 *     → poll until the commercial invoice is raised → pay in full (accountant)
 * </pre>
 *
 * <p>This collapses the §4 multi-actor model (separate Sarah / Mike / Olivia
 * drivers) into a single end-to-end scenario per virtual user, so each load user
 * carries the whole order-to-cash role bundle (sales_clerk + warehouse_clerk +
 * accountant — provisioned by {@code load-test/provision-keycloak.ps1}). Real
 * shared-resource contention still arises: many concurrent users target the same
 * SKU, so concurrent reservations and shipments hit the same {@code stock_balance}
 * row, and concurrent payments post to the same GL accounts. The deliberate
 * two-worker collision probes (TC-DOUBLE-SHIP …) live in the focused tier
 * ({@code docs/concurrent-load-test.md} §4.6).
 *
 * <p><b>Prerequisites (LIVE — not a self-contained CI test):</b>
 * <ol>
 *   <li>{@code docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d}
 *       and all services on {@code SPRING_PROFILES_ACTIVE=kafka}.</li>
 *   <li>{@code load-test/provision-keycloak.ps1 -Users <N>} — adds the
 *       {@code northwood-loadtest} direct-grant client + {@code user-0 … user-{N-1}}.</li>
 *   <li>Ample stock for the SKUs in {@code products.csv} (the default run uses the
 *       in-stock to_stock finished goods so reservation succeeds at placement).</li>
 * </ol>
 *
 * <p>Run: {@code mvn -Pload-test -pl load-test gatling:test
 * -Dgatling.simulationClass=com.northwood.loadtest.OrderToCashSimulation
 * -Dusers=50 -Dramp=60}.
 *
 * <p><b>Supply-side run.</b> To exercise the shortage / {@code to_order} paths
 * (goods receipt, work-order completion) under live load, run against the
 * {@code to-order-products.csv} feed with a generous poll, <em>and run
 * {@link OperationsDriver} in parallel</em> to play the warehouse/production actor:
 * <pre>
 *   # terminal 1 — the supply driver (warehouse_clerk + production_planner)
 *   mvn -Pload-test -pl load-test exec:java \
 *       -Dexec.mainClass=com.northwood.loadtest.OperationsDriver \
 *       -Dexec.classpathScope=test -Dexec.args="240"
 *   # terminal 2 — the customer load against undersized / to_order SKUs
 *   mvn -Pload-test -pl load-test gatling:test \
 *       -Dgatling.simulationClass=com.northwood.loadtest.OrderToCashSimulation \
 *       -Dproducts=to-order-products.csv -Dusers=30 -Dramp=60 -Dpoll=180 -Dconverge=240
 * </pre>
 * The full {@code shortage → ReplenishmentRequest → PO/WO → goods-receipt/WO-completion
 * → retry-reserve → ship → invoice → pay} loop then runs end-to-end, and the post-run
 * {@code InvariantVerifier} asserts every fulfilment saga reached a terminal state with
 * no oversell. Provision the load users with production_planner first (the
 * {@code provision-keycloak.ps1} role bundle).
 */
public class OrderToCashSimulation extends Simulation {

    private static final String SALES_BASE = System.getProperty("sales.base", "http://localhost:8082");
    private static final String INVENTORY_BASE = System.getProperty("inventory.base", "http://localhost:8083");
    private static final String FINANCE_BASE = System.getProperty("finance.base", "http://localhost:8086");
    private static final String ISSUER_URI = System.getProperty("keycloak.issuer", "http://localhost:8090/realms/northwood");
    private static final String CLIENT_ID = System.getProperty("keycloak.client", "northwood-loadtest");
    private static final String USER_PASSWORD = System.getProperty("keycloak.password", "password");
    private static final int USER_COUNT = Integer.getInteger("users", 50);
    private static final int RAMP_SECONDS = Integer.getInteger("ramp", 60);
    private static final int POLL_SECONDS = Integer.getInteger("poll", 60);
    private static final int CONVERGE_DEADLINE_SECONDS = Integer.getInteger("converge", 120);

    private static final String CUSTOMER_CODE = "CUST-001";

    // One bearer token per demo user — distinct preferred_username → distinct created_by.
    // Fetched at simulation load time; fails fast (documented) if Keycloak is not up.
    private final List<Map<String, Object>> userRows = new KeycloakTokenFeeder(ISSUER_URI, CLIENT_ID)
        .tokensFor(IntStream.range(0, USER_COUNT).mapToObj(i -> "user-" + i).toList(), USER_PASSWORD);
    private final FeederBuilder<Object> users = listFeeder(userRows).circular();

    // Seeded product UUIDs to order (productId,productSku,productName,unitCost), from the
    // demo seed (config/postgresql/northwood_erp_seed.sql). Default products.csv = the
    // in-stock to_stock finished goods, which reserve from the pool at placement. For the
    // supply-side run pass -Dproducts=to-order-products.csv (the buy-to-order carpet
    // + make-to-order chest); those park at stock_reservation_incomplete until the
    // OperationsDriver supplies the PO/WO, so use a generous -Dpoll (e.g. 180).
    private final FeederBuilder<String> products = csv(System.getProperty("products", "products.csv")).random();

    private final HttpProtocolBuilder httpProtocol = http
        .acceptHeader("application/json")
        .contentTypeHeader("application/json");

    private final ScenarioBuilder customer = scenario("order-to-cash")
        .feed(users)
        .feed(products)
        // Unique order number per virtual-user iteration; init the loop sentinels.
        .exec(session -> session
            .set("orderNumber", "LOAD-" + session.userId() + "-" + System.nanoTime())
            .set("orderStatus", "")
            .set("invoiceId", ""))
        .exec(http("place-order")
            .post(SALES_BASE + "/api/sales-orders")
            .header("Authorization", "Bearer #{token}")
            .body(StringBody("""
                {
                  "orderNumber": "#{orderNumber}",
                  "customerCode": "%s",
                  "currencyCode": "AUD",
                  "paymentTerms": "on_shipment",
                  "lines": [
                    {
                      "productId": "#{productId}",
                      "productSku": "#{productSku}",
                      "productName": "#{productName}",
                      "orderedQuantity": 1
                    }
                  ]
                }""".formatted(CUSTOMER_CODE)))
            .check(status().is(201),
                jsonPath("$.id").saveAs("orderId"),
                jsonPath("$.customerId").saveAs("customerId"),
                jsonPath("$.customerName").saveAs("customerName"),
                jsonPath("$.lines[0].lineId").saveAs("lineId")))
        // The saga reserves from the pool asynchronously (Kafka round-trip); wait
        // until the order is fully reserved before an operations actor can ship it.
        .exec(asLongAsDuring(session -> !"reserved".equals(session.getString("orderStatus")), Duration.ofSeconds(POLL_SECONDS))
            .on(pause(Duration.ofSeconds(2))
                .exec(http("poll-order")
                    .get(SALES_BASE + "/api/sales-orders/#{orderId}")
                    .header("Authorization", "Bearer #{token}")
                    .check(jsonPath("$.status").saveAs("orderStatus")))))
        .exec(http("ship")
            .post(INVENTORY_BASE + "/api/shipments")
            .header("Authorization", "Bearer #{token}")
            .body(StringBody("""
                {
                  "shipmentNumber": "SHIP-#{orderNumber}",
                  "salesOrderHeaderId": "#{orderId}",
                  "salesOrderNumber": "#{orderNumber}",
                  "customerId": "#{customerId}",
                  "customerName": "#{customerName}",
                  "warehouseCode": "MAIN",
                  "lines": [
                    {
                      "salesOrderLineId": "#{lineId}",
                      "productId": "#{productId}",
                      "productSku": "#{productSku}",
                      "productName": "#{productName}",
                      "shippedQuantity": 1,
                      "unitCost": #{unitCost}
                    }
                  ]
                }"""))
            .check(status().is(201)))
        // Finance raises the commercial invoice from the shipment event (async).
        // There is no server-side filter endpoint on customer-invoices (confirmed:
        // CustomerInvoiceController only lists all / by-id), so the harness filters
        // the full list client-side by salesOrderHeaderId via a JSONPath predicate.
        .exec(asLongAsDuring(session -> session.getString("invoiceId").isEmpty(), Duration.ofSeconds(POLL_SECONDS))
            .on(pause(Duration.ofSeconds(2))
                .exec(http("find-invoice")
                    .get(FINANCE_BASE + "/api/customer-invoices")
                    .header("Authorization", "Bearer #{token}")
                    .check(
                        jsonPath("$[?(@.salesOrderHeaderId=='#{orderId}')].id").optional().saveAs("invoiceId"),
                        jsonPath("$[?(@.salesOrderHeaderId=='#{orderId}')].totalAmount").optional().saveAs("invoiceTotal")))))
        .exec(http("pay")
            .post(FINANCE_BASE + "/api/payments/customer")
            .header("Authorization", "Bearer #{token}")
            .body(StringBody("""
                {
                  "paymentNumber": "PAY-#{orderNumber}",
                  "customerInvoiceHeaderId": "#{invoiceId}",
                  "amount": #{invoiceTotal},
                  "paymentMethod": "bank_transfer"
                }"""))
            .check(status().is(201)));

    {
        setUp(customer.injectOpen(rampUsers(USER_COUNT).during(Duration.ofSeconds(RAMP_SECONDS))))
            .protocols(httpProtocol)
            // Protocol-level gate only — the verdict that matters is the post-run
            // InvariantVerifier (conservation across schemas), run in after().
            .assertions(global().failedRequests().count().is(0L));
    }

    /**
     * Gatling lifecycle hook — runs once the injection has drained. The real
     * verdict: poll until every fulfilment saga reaches a terminal state (payment
     * → completed is itself async), then assert no-oversell + double-entry +
     * convergence ({@code docs/concurrent-load-test.md} §6, invariant 1).
     */
    @Override
    public void after() {
        new InvariantVerifier(
            System.getProperty("jdbc.url", "jdbc:postgresql://localhost:5432/northwood_erp"),
            System.getProperty("jdbc.user", "postgres"),
            System.getProperty("jdbc.password", "postgres"))
            .assertAllEventually(CONVERGE_DEADLINE_SECONDS);
    }
}
