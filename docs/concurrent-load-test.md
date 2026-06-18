# Concurrent load test — end-to-end order-to-cash under contention

A design for the load-and-concurrency test Northwood does **not** yet have (see
`docs/quality-assurance.md` §8). Its job is **correctness under contention**, not a
throughput benchmark: drive many concurrent order-to-cash flows through the *real* stack and
prove the system's invariants still hold — no oversell, the ledger balances, every saga
converges, every inbox message applies exactly once.

It runs in **two executions that share one backend and one assertion core**, differing only
in the driver at the entry point:

- **REST execution** — the *stress* run. Hundreds–thousands of concurrent orders via HTTP.
  This is where contention bites. Driven by **Gatling**.
- **Web-UI execution** — the *fidelity* run. ~10–50 concurrent browser sessions through the
  real SPA → BFF → services path, each a distinct OIDC user. Driven by **Playwright**.

The UI execution is **deliberately not** as massive as the REST one. Contention is a backend
property; once the REST run proves the backend survives a storm on shared rows, the UI run's
job is the orthogonal axis — does the actual front-end / BFF wiring drive the same correct
flows for real, concurrent, distinct users. A handful of sessions confirms front-end
concurrency-safety (session isolation, no token bleed, distinct `created_by`) without
redundantly hammering a backend a browser could never saturate.

---

## 1. What it proves

| Axis | REST execution | Web-UI execution |
|---|---|---|
| Role | stress / contention | integration / fidelity |
| Concurrency | massive (M threads, hundreds–thousands of orders) | representative (~10–50 sessions) |
| Entry point | service REST endpoints (direct or via the BFF proxy) | real SPA in a browser |
| Driver | Gatling (Java DSL) | Playwright (browser contexts) |
| Finds | races on shared resources — oversell, lost updates, idempotency gaps, ordering | front-end / BFF wiring bugs across concurrent real users |
| Identity | per-user Keycloak bearer token | per-context real OIDC login |

Both run the **same invariant suite** (§6) at the end; the UI run just asserts over its
smaller order set.

---

## 2. Substrate — one shared backend

The load-bearing choice: a **single shared Postgres and a single shared Kafka**, with all
services live. Shared infrastructure is *where contention lives* — isolated per-thread worlds
would test throughput but never the races. This rules out the in-memory acceptance DSL
(`docs/test-harness-dsl.md`): its `SynchronousBus` + fixed-point `World.settle()` are
single-threaded by construction and have no shared-resource contention by design.

```
              ┌──────────────────────────────────────────────────┐
              │  Shared backend                                   │
              │  • Postgres 17  — all service schemas + roles     │
              │  • Kafka        — SO topic ≥ 4 partitions         │
              │  • Keycloak     — N demo users, one realm         │
              │  • sales · inventory · manufacturing ·            │
              │    purchasing · finance  (SPRING_PROFILES=kafka)  │
              └──────────────────────────────────────────────────┘
                       ▲                              ▲
        ┌──────────────┴───────────┐   ┌─────────────┴────────────────┐
        │  REST driver (Gatling)   │   │  Web-UI driver (Playwright)   │
        │  massive concurrency     │   │  ~10–50 OIDC sessions         │
        └──────────────────────────┘   └───────────────────────────────┘
                       ▲                              ▲
              ┌────────┴──────────────────────────────┴────────┐
              │  Shared invariant verifier (JDBC, post-run)     │
              │  no-oversell · double-entry · idempotency ·     │
              │  per-aggregate ordering · convergence · DLT     │
              └─────────────────────────────────────────────────┘
```

Two backend lifecycles, same driver code (§9):

| | **IT mode** (CI / regression) | **Demo mode** (showcase) |
|---|---|---|
| Backend | Testcontainers, ephemeral | live `docker compose` stack + seed data |
| Observability | none | LGTM tier up, traces live (§8) |
| Scale | bounded, deterministic deadline | tuned for visual effect |
| End state | assert invariants, tear down | invariants optional finale |

> **Kafka partitions ≥ 4 is mandatory.** The SO topic is partitioned by `aggregateId`
> (`docs/messaging.md`). One partition serialises every order and hides the cross-order race
> the test exists to find; ≥ 4 forces genuine concurrent per-key streams. (Single-broker
> compose still needs the internal-topic RF caveat — `~/.claude/notes/kafka-docker.md`.)

---

## 3. The product matrix

Four products, one per `(ReplenishmentStrategy, make/buy)` combination, seeded once. Each
drives a structurally distinct fulfilment path:

| Product | Strategy | Make / buy | Path under load |
|---|---|---|---|
| `LOAD-TS-PUR` | `TO_STOCK` | purchased | reserve-from-pool → shortage (`sales_order_shortage`) → purchase requisition → PO → goods receipt → **retry-reserve** |
| `LOAD-TS-MFG` | `TO_STOCK` | manufactured | reserve-from-pool → shortage → work order + BOM explosion → completion → **retry-reserve** |
| `LOAD-TO-PUR` | `TO_ORDER` | purchased | pegged PO (`order_pegged`) → goods receipt → ship-off-peg |
| `LOAD-TO-MFG` | `TO_ORDER` | manufactured | pegged work order → manufacturing completion → ship-off-peg |

**Contention is engineered, not incidental:**

- Give the two **`TO_STOCK`** SKUs a *finite, undersized* `stock_on_hand` and point **many
  concurrent users at the same SKU**. Concurrent `StockReservationRequested` then hit the same
  `stock_balance` row — the exact `col = col + ?` race `JdbcStockBalanceWriterIT` covers,
  now end-to-end through the saga. This exercises the partial-reservation retry loop
  (`stock_reservation_incomplete` → re-reserve) at scale.
- The two **`TO_ORDER`** SKUs add pegged-supply concurrency (no pool contention, but
  concurrent replenishment-request creation and per-line peg bookkeeping).
- Mixing all four in one run exercises the pool-contention path *and* the peg path
  simultaneously — closer to a real demand storm than any single path.

Seed fixtures with the same product/customer/BOM/stock factories the acceptance DSL uses
(`a_product(...).manufacturedToOrder()`, `purchasedToOrder(...)`, `a_bom(...)`,
`stock_on_hand(...)`), so the fixtures are known-good.

---

## 4. The fulfilment journey

A virtual user's order-to-cash is more than the customer-facing calls — fulfilment needs
backend *actor* steps. Model them as distinct roles, mirroring production:

| Step | Actor | REST surface |
|---|---|---|
| Place order | customer (sales clerk) | `POST /api/sales-orders` (sales-service) |
| (shortage) raise WO / PO | system | event-driven — no call; saga + inventory route it |
| Goods receipt / WO completion | warehouse / production | inventory + manufacturing endpoints |
| Ship | warehouse | inventory shipment endpoint |
| Pay | accountant | `POST /api/payments/customer` (finance-service) |

So the Gatling/Playwright **customer scenario** does *place → (await supply) → pay*, while a
lightweight **operations driver** (a second, low-rate Gatling scenario or a scheduled robot)
performs the warehouse/production steps — goods receipt, WO completion, shipment — for orders
that have reached the relevant state. This separation keeps the customer load realistic and
lets the supply side drain at its own rate, which is what surfaces the
`stock_reservation_incomplete` dwell and retry behaviour.

> Sales placement is `POST /api/sales-orders`; cancel is `POST /api/sales-orders/{id}/cancel`;
> line amend is `POST /api/sales-orders/{id}/lines`. Customer payment is
> `POST /api/payments/customer`. Service ports are listed in `CLAUDE.md` (sales 8082,
> … reporting 8087); in IT mode they are the in-process contexts' ports, in demo mode the
> compose-published ports (or the BFF in front of them).

---

## 5. Different users — real OIDC identity

"Involving different users" is a *verifiable* requirement, not cosmetics: assert distinct
`created_by` values landed on `sales_order_header`. The audit actor is stamped server-side by
`JdbcSalesOrderRepository` from `CurrentUserAccessor.currentUsername()` → the JWT
`preferred_username` claim; commands carry no user id. So distinct users require distinct
tokens.

- **REST execution** — stand up Keycloak with N demo users (`user-0 … user-{N-1}`). A Gatling
  feeder mints/holds one bearer token per virtual user; every request carries
  `Authorization: Bearer …`. Distinct `preferred_username` → distinct `created_by`.
- **Web-UI execution** — each Playwright browser context performs a **real OIDC login** as a
  distinct demo user, so identity flows the genuine SPA → BFF → services path.

> **BFF prerequisite for the UI run.** The demo BFF normally stamps a *single* synthetic
> identity via a shared-secret bypass header (`docs/infrastructure.md`); that cannot produce
> different users. The UI execution therefore requires the BFF/SPA configured to accept **real
> OIDC tokens** (bypass disabled) so per-context login produces distinct `created_by`. This is
> a run-mode toggle, not a code change to the bypass path.

---

## 6. Invariants asserted

A load test that asserts wall-clock latency is flaky. Assert **conservation and convergence**
instead — verified by a JDBC verifier after the injection drains (and reusable as the demo's
finale). Each maps to a real failure mode:

1. **Eventual convergence.** Poll (Awaitility) until *every* saga reaches a terminal state —
   `COMPLETED` / `REJECTED` / `COMPENSATED` — within a generous deadline, else fail with a
   census of stuck sagas and their states. Catches deadlock, lost events, lease starvation.
2. **No oversell** (the headline race). Per `TO_STOCK` SKU:
   `Σ reserved ≤ on_hand + Σ replenished`, and `stock_balance.on_hand_quantity` never goes
   negative. This is what concurrent reservation on a shared row can violate.
3. **Double-entry holds.** Every involved GL account nets correctly; per completed order
   `Σ invoiced = Σ shipped = Σ paid` (the conservation invariant of
   `docs/composed-state-machines.html`). A lost update under load shows up here as an
   unbalanced ledger.
4. **Idempotency under redelivery.** Each `inbox_message` applied exactly once — no duplicate
   journal entry, reservation, or shipment. Concurrency + the advisory-lock dedup gate
   (`northwood.inbox.dedup-strategy`) is exactly the rebalance-window TOCTOU `docs/messaging.md`
   describes; crank consumer concurrency to force rebalances mid-run and re-assert.
5. **Per-aggregate ordering.** Events for a single SO appear in saga-legal order (partition
   key = `aggregateId`) even though orders interleave globally.
6. **Empty DLT.** No saga wedged on a `CHECK` violation (the `23514` → DLT-loop failure mode of
   `docs/validations.md`); the dead-letter topic is empty at the end.

Metrics are **reported, not asserted** (so they never flake the build): orders/sec, p50/p95
time-to-`COMPLETED` per product type, `TO_STOCK` retry-loop count, max
`stock_reservation_incomplete` dwell, DB-pool saturation. Gatling's own report covers the
HTTP-level latency picture.

---

## 7. Running the load with Gatling

### Why Gatling, not JMeter

The goal is correctness, and the verdicts live in **Postgres across schemas** and in **saga
convergence** — not in HTTP responses. JMeter asserts on responses; the cross-cutting end-state
invariants (§6) would force fragile JDBC samplers + BeanShell. Gatling is **JVM-native and
code-first**: it lives in the Maven build, mints Keycloak tokens in plain Java, has an `after`
lifecycle hook to run the JDBC invariant verifier in-process, and produces an excellent latency
report for the metrics side. JMeter remains a fine choice only for the *throughput benchmark*
this test deliberately is not. (A load tool only helps the REST path either way — the UI path
is Playwright.)

### Module layout

A dedicated module kept **out of the default reactor build** so `mvn verify` stays fast — the
load run is explicit:

```
load-test/
  pom.xml                       # gatling-maven-plugin + gatling-charts-highcharts; not in <modules> of the default profile
  src/test/java/com/northwood/loadtest/
    OrderToCashSimulation.java  # the Gatling Java-DSL simulation
    KeycloakFeeder.java         # mints one bearer token per virtual user
    OperationsSimulation.java   # warehouse/production drain (ship, goods-receipt, WO-complete)
    InvariantVerifier.java      # JDBC post-run checks (§6), shared with the UI execution
```

Wire it under a Maven profile (e.g. `-Pload-test`) so it is reachable only on demand.

### The simulation (Java DSL, sketch)

```java
public class OrderToCashSimulation extends Simulation {

    HttpProtocolBuilder http = http.baseUrl(System.getProperty("sales.base", "http://localhost:8082"));

    // one row per virtual user: a pre-minted Keycloak token + the user's name
    FeederBuilder<String> users = KeycloakFeeder.tokensFor(/* N */ 50).circular();

    // randomised product mix across the four combinations
    FeederBuilder<Object> products = listFeeder(List.of(
        Map.of("sku", "LOAD-TS-PUR"), Map.of("sku", "LOAD-TS-MFG"),
        Map.of("sku", "LOAD-TO-PUR"), Map.of("sku", "LOAD-TO-MFG"))).random();

    ScenarioBuilder customer = scenario("place-and-pay")
        .feed(users).feed(products)
        .exec(http("place-order").post("/api/sales-orders")
            .header("Authorization", session -> "Bearer " + session.getString("token"))
            .body(StringBody("""
                {"customerId":"#{customerId}","lines":[{"productCode":"#{sku}","quantity":1}]}"""))
            .check(status().is(201), jsonPath("$.id").saveAs("orderId")))
        // await supply: poll the order until it is ready to invoice/pay
        .asLongAs(session -> !"SUPPLY_SECURED".equals(session.getString("sagaState")), "poll")
            .on(pause(Duration.ofSeconds(2))
                .exec(http("poll-order").get("/api/sales-orders/#{orderId}")
                    .header("Authorization", session -> "Bearer " + session.getString("token"))
                    .check(jsonPath("$.sagaState").saveAs("sagaState"))))
        .exec(http("pay").post("/api/payments/customer")
            .header("Authorization", session -> "Bearer " + session.getString("token"))
            .body(StringBody("""{"orderId":"#{orderId}","amount":"#{amountDue}"}""")));

    {
        setUp(
            customer.injectOpen(rampUsers(2000).during(Duration.ofMinutes(5)))
        ).protocols(http)
         .assertions(global().failedRequests().count().is(0L));  // protocol-level gate only

        // deep invariants run after the injection drains (the verdict that matters)
        after(() -> InvariantVerifier.assertAll(jdbcUrl));
    }
}
```

Run a separate `OperationsSimulation` (low constant rate) alongside, or as a second `setUp`
scenario, to drain shipment / goods-receipt / WO-completion for orders that have reached the
right state. Gatling's `after {}` hook calls the shared `InvariantVerifier` so the §6 checks
run in the same JVM as the load.

### Commands

```powershell
# 1. bring up the shared backend (IT mode uses Testcontainers; demo mode uses compose — §8)
docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d

# 2. seed the four load SKUs + N Keycloak users (one-off bootstrap step / script)
mvn -Pload-test -pl load-test exec:java -Dexec.mainClass=com.northwood.loadtest.Bootstrap

# 3. run the REST load
mvn -Pload-test -pl load-test gatling:test -Dgatling.simulationClass=com.northwood.loadtest.OrderToCashSimulation

# report: load-test/target/gatling/ordertocashsimulation-<ts>/index.html
```

Tune scale with JVM props (`-Dusers=2000 -Dramp=300`), not code edits.

---

## 8. Demo mode — making the load test a showcase

The same driver, pointed at the **live `docker compose` stack** with the LGTM observability
tier up (`docs/observability.md`), becomes the most visceral demo in the project: the system
visibly holding under a storm of concurrent users.

What the audience watches while the load runs:

- **Grafana** — live orders/sec, traces fanning sales → inventory → manufacturing / purchasing
  → finance, `stock_balance` depleting then replenishing for the `TO_STOCK` SKUs, saga-state
  transition counts. Drill into a *single* sales order *during* the storm via the
  trace↔log↔metric click-through.
- **A wall of browser windows** (the Web-UI execution) — each a distinct Keycloak user placing
  and paying orders simultaneously, flowing through fulfilment in real time. This is the
  centerpiece; it does not need REST-scale concurrency to be compelling.
- **The finale** — run the §6 `InvariantVerifier` live: "5,000 orders across 50 users; zero
  oversell; the ledger balances to the cent; the dead-letter topic is empty."

Demo run:

```powershell
# full stack + seed + observability sidecars
docker compose -f docker-compose.yml -f docker-compose.seed.yml -f docker-compose.observability.yml up -d
# open Grafana (per docs/observability.md), then drive load at a watchable rate:
mvn -Pload-test -pl load-test gatling:test -Dgatling.simulationClass=com.northwood.loadtest.OrderToCashSimulation -Dusers=300 -Dramp=120
# and/or launch the Playwright UI sessions (Chrome) for the browser wall
```

In demo mode the invariant verifier is an optional flourish rather than a build gate; the
point is the live picture, with the green verdict as the payoff.

---

## 9. Shared code, two drivers

One `OrderDriver` SPI keeps the two executions honest — same seeding, same matrix, same
verifier; only the entry point differs:

```java
interface OrderDriver {
    OrderHandle placeOrder(User user, String productCode, int qty);
    void pay(User user, OrderHandle order, Money amount);
    // ship / goods-receipt / WO-completion are driven by the operations role, not the customer
}
```

- `RestOrderDriver` — the Gatling scenario above (or a thin HTTP-client driver for the
  Testcontainers IT-mode harness).
- `WebUiOrderDriver` — Playwright: one browser context per user, real OIDC login, then UI
  clicks that place and pay. Asserts session isolation (no token bleed) and, via the shared
  verifier, the same invariants over its smaller order set.

---

## 10. Gotchas baked in

- **Per-thread DB connection.** Hikari pool sized ≥ the REST driver's peak concurrency, or
  acquisition contention masquerades as a saga stall.
- **Logback/Loki appender fork hazard.** An erroring Loki appender fails the *next*
  `@SpringBootTest` context in the same fork (`~/.claude/notes/spring-boot-4.md`). Keep the
  IT-mode harness in its own fork.
- **No parallel JUnit methods.** The shared schema means `@Execution` stays default — the
  concurrency is *inside* the test, not across methods.
- **OTel HTTP span names.** Spans are `http <lowercase-method> <route>`, not `POST /…`; any
  Tempo/TraceQL demo panel must match on the lowercase shape (`~/.claude/notes/spring-boot-4.md`).
- **Unique order numbers per (user, iteration)** to avoid collisions in any business-id
  registry.

---

## 11. Status & non-goals

- **Status: design only.** No `load-test` module exists yet; this fills the gap
  `docs/quality-assurance.md` §8 names ("built for it — per-aggregate partition keys,
  `SKIP LOCKED` drains — but not exercised under load").
- **Not a throughput benchmark.** Latency/throughput are reported, never asserted. A dedicated
  perf-tuning exercise (capacity planning, GC tuning) is a separate effort.
- **Not chaos testing.** Network partitions / broker kills / node loss are a further tier not
  in scope here.
- **UI scale is intentionally bounded.** The Web-UI execution validates the front-end path, not
  backend capacity; do not grow it to match the REST run.
