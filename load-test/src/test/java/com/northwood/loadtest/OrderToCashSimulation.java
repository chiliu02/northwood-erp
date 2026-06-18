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
 * §7) — the <strong>stress</strong> run. Many concurrent users place an order, wait
 * for it to be invoiced, then pay it, each as a distinct Keycloak identity.
 *
 * <p><b>This is a template that runs against a LIVE, seeded stack — it is not, and
 * cannot be, a self-contained CI test.</b> Before running, bring the stack up
 * ({@code docker compose ... up -d} + all services on {@code SPRING_PROFILES_ACTIVE=kafka}
 * + Keycloak with the demo users), then supply the live coordinates via system
 * properties (see {@code load-test/README.md}). Items still needing confirmation
 * against the running system are marked {@code TODO(live)} — they cannot be settled
 * without the stack up, and were not exercised when this slice was written.
 *
 * <p>Run: {@code mvn -Pload-test -pl load-test gatling:test
 * -Dgatling.simulationClass=com.northwood.loadtest.OrderToCashSimulation}.
 */
public class OrderToCashSimulation extends Simulation {

    private static final String SALES_BASE = System.getProperty("sales.base", "http://localhost:8082");
    private static final String FINANCE_BASE = System.getProperty("finance.base", "http://localhost:8086");
    private static final String ISSUER_URI = System.getProperty("keycloak.issuer", "http://localhost:8090/realms/northwood");
    private static final String CLIENT_ID = System.getProperty("keycloak.client", "northwood-erp");
    private static final String USER_PASSWORD = System.getProperty("keycloak.password", "password");
    private static final int USER_COUNT = Integer.getInteger("users", 50);
    private static final int RAMP_SECONDS = Integer.getInteger("ramp", 60);

    // One bearer token per demo user — distinct preferred_username → distinct created_by.
    // Fetched at simulation load time; fails fast (documented) if Keycloak is not up.
    private final List<Map<String, Object>> userRows = new KeycloakTokenFeeder(ISSUER_URI, CLIENT_ID)
        .tokensFor(IntStream.range(0, USER_COUNT).mapToObj(i -> "user-" + i).toList(), USER_PASSWORD);
    private final FeederBuilder<Object> users = listFeeder(userRows).circular();

    // Seeded product UUIDs to order — supply via src/test/resources/products.csv
    // (columns: productId,productSku,productName). TODO(live): populate from the
    // demo seed (config/postgresql/northwood_erp_seed.sql) for the SKUs under test.
    private final FeederBuilder<String> products = csv("products.csv").random();

    private final HttpProtocolBuilder httpProtocol = http
        .acceptHeader("application/json")
        .contentTypeHeader("application/json");

    private final ScenarioBuilder customer = scenario("order-to-cash")
        .feed(users)
        .feed(products)
        // Unique order + payment numbers per virtual-user iteration.
        .exec(session -> session
            .set("orderNumber", "LOAD-" + session.userId() + "-" + System.nanoTime())
            .set("sagaState", "")
            .set("invoiceId", ""))
        .exec(http("place-order")
            .post(SALES_BASE + "/api/sales-orders")
            .header("Authorization", "Bearer #{token}")
            .body(StringBody("""
                {
                  "orderNumber": "#{orderNumber}",
                  "customerCode": "CUST-001",
                  "currencyCode": "AUD",
                  "lines": [
                    {
                      "productId": "#{productId}",
                      "productSku": "#{productSku}",
                      "productName": "#{productName}",
                      "orderedQuantity": 1
                    }
                  ]
                }"""))
            .check(status().is(201), jsonPath("$.id").saveAs("orderId")))
        // Wait until an operations actor (the OperationsSimulation / a manual driver)
        // has shipped the order and finance has raised its commercial invoice.
        // TODO(live): confirm the lookup path + JSON field for the invoice id.
        .exec(asLongAsDuring(session -> session.getString("invoiceId").isEmpty(), Duration.ofMinutes(5))
            .on(pause(Duration.ofSeconds(3))
                .exec(http("find-invoice")
                    .get(FINANCE_BASE + "/api/customer-invoices?salesOrderHeaderId=#{orderId}")
                    .header("Authorization", "Bearer #{token}")
                    .check(jsonPath("$[0].id").optional().saveAs("invoiceId")))))
        .exec(http("pay")
            .post(FINANCE_BASE + "/api/payments/customer")
            .header("Authorization", "Bearer #{token}")
            .body(StringBody("""
                {
                  "paymentNumber": "PAY-#{orderNumber}",
                  "customerInvoiceHeaderId": "#{invoiceId}",
                  "amount": 100.00,
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

    /** Gatling lifecycle hook — runs once the injection has drained. The real verdict. */
    @Override
    public void after() {
        new InvariantVerifier(
            System.getProperty("jdbc.url", "jdbc:postgresql://localhost:5432/northwood_erp"),
            System.getProperty("jdbc.user", "postgres"),
            System.getProperty("jdbc.password", "postgres")).assertAll();
    }
}
