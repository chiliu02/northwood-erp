# Concurrent load test — end-to-end order-to-cash under contention

A design for the load-and-concurrency test Northwood does **not** yet have (see
`docs/quality-assurance.md` §8). Its job is **correctness under contention**, not a
throughput benchmark: drive many concurrent order-to-cash flows through the *real* stack and
prove the system's invariants still hold — no oversell, the ledger balances, every saga
converges, every inbox message applies exactly once.

The entry-point driver is **Gatling**: hundreds–thousands of concurrent orders via HTTP against
the live service REST endpoints. This is where contention bites. A shared JDBC invariant
verifier asserts the conservation invariants after the load drains.

---

## 1. What it proves

| Axis | REST execution |
|---|---|
| Role | stress / contention |
| Concurrency | massive (M threads, hundreds–thousands of orders) |
| Entry point | service REST endpoints (direct or via the BFF proxy) |
| Driver | Gatling (Java DSL) |
| Finds | races on shared resources — oversell, lost updates, idempotency gaps, ordering |
| Identity | per-user Keycloak bearer token |

The run executes the **invariant suite** (§6) at the end, over the order set it placed.

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
                                     ▲
                       ┌─────────────┴────────────┐
                       │  REST driver (Gatling)    │
                       │  massive concurrency      │
                       └───────────────────────────┘
                                     ▲
              ┌──────────────────────┴──────────────────────────┐
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

## 4. Actors, eligibility, and work-selection

Order-to-cash is not one virtual user — it is several **roles acting concurrently**, each
picking work from its own pool. The naming used throughout (and in the demo): **Sarah** places
orders, **Mike** ships, **Olivia** receives payments; the supply side (goods receipt, work-order
completion) follows the same model with receiving/production workers. The customer-facing steps
and the operations steps run as **separate concurrent drivers** so the customer load stays
realistic while the supply/cash side drains at its own rate — which is exactly what surfaces the
`stock_reservation_incomplete` dwell, the retry loop, and the out-of-order arrivals.

### 4.1 Each actor sees only an *eligible pool*

No actor picks from all orders — each is gated by saga/aggregate state, so "randomly picks goods
to ship" really means *randomly picks among the orders that are currently shippable*. Picking
outside the pool is a no-op/reject, not useful load.

| Actor | Action | Eligibility (what enters the pool) | REST surface |
| --- | --- | --- | --- |
| **Sarah** (sales clerk) | place order | none — she is the source | `POST /api/sales-orders` |
| Sarah / manager | amend / cancel | order exists ∧ `!anyLineShipped()` ∧ not terminal | `POST /api/sales-orders/{id}/lines`, `…/cancel` |
| receiving | goods receipt | a dispatched replenishment request with a PO awaiting goods | inventory goods-receipt endpoint |
| production | WO completion | a released work order | manufacturing endpoint |
| **Mike** (warehouse) | ship | saga at `SUPPLY_SECURED` (lines reserved / supply pegged-in) | inventory shipment endpoint |
| **Olivia** (accountant) | receive payment | an *open* customer invoice exists | `POST /api/payments/customer` |

> **Olivia's pool depends on payment terms**, so it is not simply "shipped orders." Deposit and
> prepayment invoices enter her pool **before** shipment (saga `awaiting_prepayment`); the
> commercial invoice enters **after** `ShipmentPosted`; COD auto-settles without her. Running the
> full product matrix across mixed terms keeps her pool a realistic blend of pre- and post-ship
> invoices.

### 4.2 Selection strategy — within the eligible pool

Two strategies, both worth running because they cover different failure shapes:

- **FIFO** — oldest-eligible first (by eligibility timestamp). Deterministic given a fixed arrival
  order; models a disciplined work queue.
- **Random** — uniform pick among the *current* eligible set, from a **seeded** RNG with the seed
  logged for reproducibility; models real-world arbitrary pickup.
- *(variant)* **priority** — highest production-board priority first, mirroring the real
  reprioritisation path; useful for the supply side, not core to o2c.

### 4.3 Same-role concurrency — disjoint vs overlapping

The key dimension the question raises: when **several Mikes (or several Olivias) share one pool**,
do they claim disjoint work or can two land on the same order?

- **Claimed / disjoint** — a shared work queue with an atomic take (`poll()` removes), mirroring
  `SELECT … FOR UPDATE SKIP LOCKED`. Each order goes to exactly one worker. Tests throughput and
  that the drain neither drops nor duplicates work.
- **Overlapping / with replacement** — each worker independently queries the backend's eligible
  pool; the same order stays visible to multiple workers until its state advances out of
  eligibility. **This is the race-finder** — two ship commands (or two payments) on one aggregate
  collide on purpose, exercising the optimistic-version guard and command idempotency.

### 4.4 The selection × overlap matrix

| Selection ↓ / Overlap → | **Claimed (disjoint)** | **Overlapping (with replacement)** |
| --- | --- | --- |
| **FIFO** | orderly drain; baseline throughput; no collision | **thundering herd** — every worker converges on the *same* oldest-eligible order ⇒ maximal same-aggregate command collision (sharpest idempotency probe) |
| **Random** | realistic spread; no double-work | realistic load **plus scattered collisions** ⇒ idempotency under lifelike conditions (the **default** race hunt) |

Reading it: FIFO + overlapping is a deliberate worst case (all workers target the head); random +
overlapping is the realistic default; the claimed column validates the clean drain and that
claiming itself is correct. **All four cells are run** — they are not redundant.

### 4.5 Cross-role interleaving

Sarah, Mike, and Olivia running together (different rates) produces the genuinely interesting
orderings the single-actor view never reaches: payment landing before an order is fully shipped, a
cancel racing a shipment, a partial ship followed by a replenished re-ship. Per-order *event*
ordering still holds at the messaging layer (partition key = `aggregateId`,
`docs/messaging.md`); the contention "random + overlapping + multi-role" creates is at the
**command/aggregate layer** — concurrent commands on one aggregate and out-of-order actor arrival.

### 4.6 Test-case catalogue — different cases for different situations

The dimensions above (§4.1–4.5) compose into a suite of **independently-runnable test cases**, not
one monolith. Each pins a situation, fixes a generator config, and checks the subset of properties
(§6) it targets. Run any case alone; CI runs the cheap focused ones, while the stress and demo
cases run on demand.

| ID                                        | Situation                          | Config (selection · overlap · products · terms · scale) | Tier            | Properties (§6)                        |
| ----------------------------------------- | ---------------------------------- | ------------------------------------------------------- | --------------- | -------------------------------------- |
| **TC-STRESS**                             | the headline race hunt             | random · overlapping · all 4 · mixed · massive          | Gatling         | all (1–6)                              |
| **TC-THROUGHPUT**                         | clean-drain baseline + metrics     | random · claimed · all 4 · mixed · massive              | Gatling         | 1, 2                                   |
| **TC-HERD**                               | idempotency thundering herd        | FIFO · overlapping · 1 SKU · on-ship · ≥ 8 workers      | jqwik / Gatling | 4 + no-double-effect                   |
| **TC-DOUBLE-SHIP**                        | two Mikes, one order               | random · overlapping · any · — · 2 shippers             | jqwik-IT        | exactly-one ship + COGS                |
| **TC-DOUBLE-PAY**                         | two Olivias, one invoice           | — · overlapping · any · on-ship · 2 payers              | jqwik-IT        | 3 + no over-allocation                 |
| **TC-CANCEL-SHIP**                        | cancel races ship                  | — · overlapping · any · — · cancel + ship               | jqwik-IT        | `anyLineShipped()` gate, no half-state |
| **TC-COMPENSATE-PEGGED**                  | cancel a to_order line pre-receipt | — · — · TO_ORDER · — · cancel before goods receipt      | live-IT         | multi-leg compensation: PO/WO withdrawn, no orphan |
| **TC-PAY-FIRST**                          | pay before fully shipped           | — · — · any · deposit/prepay · cross-role               | jqwik-IT        | completion gate (1)                    |
| **TC-PARTIAL-SHIP**                       | multi-line, partial then re-ship   | — · — · TO_STOCK · — · staged supply                    | jqwik-IT        | 2 + line-fold rollup                   |
| **TC-SUPPLY-DUP**                         | dup goods-receipt / WO-completion  | — · overlapping · supply side · — · 2 receivers         | jqwik-IT        | 4 + single top-up                      |
| **TC-PATH-{TS-PUR,TS-MFG,TO-PUR,TO-MFG}** | one product path saturated         | random · overlapping · **one** · mixed · high           | Gatling         | path-specific 1–4                      |
| **TC-DEMO**                               | live showcase                      | tuned for watchability over the live stack              | demo            | optional finale                        |

"—" means the axis is not what the case is about (use the default). The matrix sweep (§4.4) is just
TC-STRESS / TC-THROUGHPUT / TC-HERD run across its four cells; the focused `TC-*` cases are the
race probes — each a short scenario that fails fast and points at one property.

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

Stand up Keycloak with N demo users (`user-0 … user-{N-1}`). A Gatling feeder mints/holds one
bearer token per virtual user; every request carries `Authorization: Bearer …`. Distinct
`preferred_username` → distinct `created_by`.

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

> **Implementation status (all six wired).** `InvariantVerifier` now asserts every invariant
> above. 1–3 are the original JDBC checks. **4 (idempotency)** is a per-schema check that no
> `<schema>.inbox_message` holds a duplicate `(message_id, handler_name)` — the table's UNIQUE is
> `(message_id, handler_name, processed_at)`, so partitioning means the constraint does *not*
> prevent a duplicate apply (the rebalance-window TOCTOU of `docs/messaging.md`); a duplicate row
> is a real dedup-gate miss — plus a quantity-effect gate (no over-shipped / over-reserved line).
> **5 (per-aggregate ordering)** is asserted via the forbidden end-state combinations an
> out-of-order / lost apply would leave — an order both shipped and cancelled, or a `completed`
> saga with an unshipped line (end-state JDBC can't observe the *history* directly; these are the
> observable consequences). **6 (empty DLT)** is the one Kafka-side check — the `InvariantVerifier`
> opens an AdminClient (`kafka.bootstrap`, default `localhost:9092`) and asserts every
> `*.events.dlt` / `*.events.dlt.parked` topic has zero records (earliest == latest per partition).
> The remaining gap is the *active* "force consumer rebalances mid-run and re-assert" sub-step
> (invariant 4): the assertion is wired, but provoking rebalances during load still needs a
> consumer restart mid-run (an operational variant), not a verifier change.

### 6.1 This is a property-based test — and the tools that run it

The shape above *is* property-based testing applied to a concurrent, stateful system:

- **Properties** = invariants 1–6, universally quantified over *all* legal interleavings ("for any
  schedule of Sarah/Mike/Olivia, the ledger balances and nothing oversells") — not example-based
  assertions.
- **Generators** = the randomized inputs: which user (Keycloak feeder), which of the four products,
  which payment terms, the selection strategy and worker-overlap (§4), and the arrival timing — all
  from a **seeded** RNG, with the seed logged so any failing run replays.
- **The "for all" quantifier** is approximated by *volume* — thousands of randomized interleavings
  rather than enumerated cases — the same statistical-coverage bet QuickCheck makes.

**Frameworks / tools** (yes — this need not be hand-rolled):

- **jqwik** (the JUnit 5 property engine) for the in-JVM, controllable cases (`TC-DOUBLE-SHIP …
  TC-SUPPLY-DUP`, `TC-HERD`). Its **stateful / `Action`-sequence** model fits exactly: generate a
  random sequence of actor commands (place / ship / pay / cancel / receive), run them against the
  real services on the Testcontainers backend, and assert the properties after each step. jqwik
  also **shrinks** — on failure it minimises the command sequence toward the smallest interleaving
  that still breaks the property, the one thing a hand-rolled loop cannot give you.
- **Gatling** for the high-volume cases (`TC-STRESS`, `TC-THROUGHPUT`, `TC-PATH-*`): the "generator"
  is the feeders + seeded RNG and the property check is the `InvariantVerifier` in the `after {}`
  hook (§7).
- The rigorous framing is **model-based / linearizability** testing: check that the random
  concurrent history linearises against a sequential model — here the saga's legal transition
  function (`SalesOrderFulfilmentSaga.ALL_STATES` + allowed edges) plus the conservation arithmetic
  (stock, double-entry). jqwik's stateful model expresses this directly; a Jepsen-style external
  history + linearizability checker is possible but out of scope.

**Honest limit on shrinking.** Deterministic shrinking needs a *controllable scheduler*; with real
Kafka + Postgres + wall-clock timing a failure may not reproduce identically. So jqwik shrinking is
used for the in-JVM Testcontainers cases (where the command sequence is the variable); for the
Gatling stress run, "shrinking" degrades to **seed + full command/event trace capture** for replay,
and the minimal case is then reconstructed as a jqwik case. A deterministic in-JVM replay (a
pluggable scheduler over the synchronous-bus harness, `docs/test-harness-dsl.md`) is a stretch goal.

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
this test deliberately is not.

### Module layout

A dedicated module kept **out of the default reactor build** so `mvn verify` stays fast — the
load run is explicit:

```
load-test/
  pom.xml                       # gatling-maven-plugin + gatling-charts-highcharts; not in <modules> of the default profile
  src/test/java/com/northwood/loadtest/
    OrderToCashSimulation.java  # the Gatling Java-DSL simulation (-Dproducts selects the feed)
    KeycloakTokenFeeder.java    # mints one bearer token per virtual user
    OperationsDriver.java       # warehouse/production supply drain (goods-receipt + WO-complete); standalone poller run alongside
    ConcurrentRaceProbesTest.java # focused two-worker collisions (§4.6)
    InvariantVerifier.java      # JDBC post-run checks (§6)
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

**As built:** the supply side is a standalone `OperationsDriver` poller (the
"standalone poller" option) rather than a `OperationsSimulation` Gatling scenario — run it
alongside `OrderToCashSimulation -Dproducts=to-order-products.csv` (the buy-to-order carpet +
make-to-order chest, undersized) so the shortage paths close. It discovers outstanding POs +
released WOs by JDBC and posts the real `POST /api/goods-receipts` / `POST /api/work-orders/{id}
/operations/{seq}/complete` actions (warehouse_clerk + production_planner). The Gatling sim's
`after {}` hook still calls the shared `InvariantVerifier` so the §6 checks run in the same JVM
as the load. Run commands: `load-test/README.md` → *Supply-side run*.

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
  trace↔log↔metric click-through. This is the centerpiece.
- **The finale** — run the §6 `InvariantVerifier` live: "5,000 orders across 50 users; zero
  oversell; the ledger balances to the cent; the dead-letter topic is empty."

Demo run:

```powershell
# full stack + seed + observability sidecars
docker compose -f docker-compose.yml -f docker-compose.seed.yml -f docker-compose.observability.yml up -d
# open Grafana (per docs/observability.md), then drive load at a watchable rate:
mvn -Pload-test -pl load-test gatling:test -Dgatling.simulationClass=com.northwood.loadtest.OrderToCashSimulation -Dusers=300 -Dramp=120
```

In demo mode the invariant verifier is an optional flourish rather than a build gate; the
point is the live picture, with the green verdict as the payoff.

---

## 9. Shared verifier, one driver

The Gatling scenario (§7) is the single entry point; the assertion core is factored out so the
same checks run in the load JVM and standalone as the demo finale:

```java
interface OrderDriver {
    OrderHandle placeOrder(User user, String productCode, int qty);
    void pay(User user, OrderHandle order, Money amount);
    // ship / goods-receipt / WO-completion are driven by the operations role, not the customer
}
```

- `RestOrderDriver` — the Gatling scenario above (or a thin HTTP-client driver for the
  Testcontainers IT-mode harness).
- `InvariantVerifier` — shared between the in-process `after {}` hook and the standalone demo
  finale (`exec:java`), so the same §6 verdict is produced either way.

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
- **Shared-DB cross-test contamination → reset before each test.** The shared backend (§2) is
  deliberate, but it means one test's residue is the next test's input. The post-run
  `InvariantVerifier` convergence check (§6, invariant 1) polls for *every* saga in the database
  to reach a terminal state — it cannot distinguish a leftover from a fresh order. So a test that
  intentionally leaves orders non-terminal makes the *next* test's verifier fail with a census of
  the previous test's orders (a false negative). The sharpest case is the focused race probes
  (§4.6): they ship / cancel / partial-ship orders but never pay them, so those sagas correctly
  sit at `wait_for_completion` forever. **Fix:** don't mandate a run order — reset to a clean,
  seeded, stock-bumped slate before each test (`load-test/reset-data.ps1`, which recreates only
  the Postgres data volume and leaves Kafka / Keycloak / the services running). This makes the
  tests order-independent.

---

## 11. Current status, honest assessment, and gaps

### 11.1 What is implemented today

| Tier | What it drives | What it proves | Verified |
|---|---|---|---|
| **In-JVM property suite** (`test-harness` `o2c.OrderToCashPropertyTest`, jqwik) | All four archetypes (to_stock/to_order × purchased/manufactured) incl. the supply legs (goods receipt, work-order completion), through the **real** saga + inbox handlers + serde over the in-memory `World` | Saga/handler **logic** correctness under an arbitrary *mix and ordering* of orders; convergence, no-oversell, double-entry per run | ✅ CI-green (100 jqwik tries) |
| **REST execution** (`load-test` `OrderToCashSimulation`, Gatling) | Many concurrent distinct-user orders, **ample-stock customer-forward path only** (place → reserve → ship → invoice → pay), real Postgres + Kafka | **All six §6 invariants** hold under concurrent reservation on shared `stock_balance` rows + concurrent GL posting (convergence, no-oversell, double-entry, idempotency, ordering, empty-DLT) | ✅ live: 50 distinct-user orders, all six asserted invariants held |
| **Focused race probes** (`load-test` `ConcurrentRaceProbesTest`) | 7 probes: barrier-synchronised two-worker collisions (TC-DOUBLE-PAY, TC-DOUBLE-SHIP, TC-CANCEL-SHIP, TC-SUPPLY-DUP) + sequential gate/fold probes (TC-COMPENSATE-PEGGED, TC-PAY-FIRST, TC-PARTIAL-SHIP) | Command-layer exactly-once / no-half-state; order-pegged supply withdrawn (not orphaned) on a to_order cancel; prepayment completion gate; partial-ship line fold; single supply top-up | ✅ all 7 green (0 skipped). The two bugs the race probes found — double-ship over-ship, cancel-vs-ship half-state — are **fixed** and guarded; TC-COMPENSATE-PEGGED found + pinned the missing `purchasing.events` subscription; TC-PAY-FIRST/PARTIAL-SHIP/SUPPLY-DUP confirm the gate, line-fold, and single-top-up properties |

### 11.2 Does the suite *ensure* correct concurrent behaviour? — No.

It **raises confidence and has already caught real defects**, but it does not *guarantee*
correctness. The honest assessment:

- **The strongest evidence is the probe hit-rate.** Of the first three command-layer collision
  probes written, **two immediately surfaced real cross-service races** — concurrent double-ship
  both succeed and double-decrement `on_hand` (inventory over-ship, while sales caps the line and
  finance issues one invoice); a simultaneous cancel + ship can leave an order **both shipped and
  cancelled** (the `anyLineShipped()` cancel gate reads sales-local state that lags the async
  shipment event). A 2-in-3 hit rate on a first batch strongly implies **more unprobed races
  exist**. *(Both races found this way have since been fixed — the double-ship over-ship via a
  synchronous per-line ship claim, and the cancel-vs-ship half-state via a two-phase
  inventory-arbitrated cancel — and their probes re-enabled as guards. The point stands: the
  probes earned their keep on the first batch, so more of the surface should be probed.)*
- **The in-JVM tier cannot find true races.** `World.settle()` is single-threaded and each order
  owns its own product — by construction it exercises *ordering*, never *shared-row contention*.
- **The REST run asserts end-state, not linearizability.** It reads the DB *after* the storm
  drains, so it is blind to races that produce a self-consistent-but-wrong state, transient
  violations that heal, and anything about the *history* (it never observes the interleaving).
- **The live verifier now implements all 6 designed invariants** (§6) — no-oversell, double-entry,
  and convergence were the original three; **idempotency-under-redelivery (4), per-aggregate
  ordering (5), and empty-DLT (6) are now wired** (idempotency as a per-schema duplicate-inbox-apply
  + over-effect check, ordering as the forbidden-end-state combinations, empty-DLT via a Kafka
  AdminClient offset check). The one residual is the *active* "force consumer rebalances mid-run and
  re-assert" sub-step of invariant 4 — the assertion runs, but provoking rebalances during the load
  still needs a consumer restart mid-run (an operational variant). It remains an **end-state**
  verifier (the next bullet), so it is still blind to transient-then-healed violations and to history.
- **Methodological ceiling.** "For all interleavings" is approximated by volume + randomness —
  statistical, not exhaustive. There is no deterministic scheduler, no model checker, no
  linearizability oracle, and no fault injection. Exactly-once *across failures* (the hard part
  of distributed correctness) is untested.

**Treat the suite as "increases confidence and finds bugs," not "guarantees correctness."**

### 11.3 Gaps / TODO to move toward "ensures correctness"

Ranked by how much each closes the gap above:

1. **Both found races are fixed and guarded** (done). (a) Double-ship over-ship — a synchronous
   per-`sales_order_line` ship claim in inventory (`sales_order_line_facts` carries
   `ordered_quantity` + a cumulative `shipped_quantity`, claimed atomically + row-locked before any
   stock decrement; the make-to-order path ships with `reserved = 0`, so the claim caps on *ordered*,
   not reserved). (b) Cancel-vs-ship half-state — cancel is now **two-phase**: sales only *requests*
   cancellation, and inventory arbitrates on the same `sales_order_line_facts` rows (a
   cancellation-claim flips `cancelled` where nothing shipped; the ship-claim refuses a cancelled
   line). Whichever of the two claims commits first wins, so the order is never both shipped and
   cancelled — no synchronous cross-service call or shared lock needed (which the schema-per-service +
   outbox-only architecture forbids). The remaining work is **breadth**: the items below.
2. **The 3 missing live invariants are now implemented** (done). `InvariantVerifier` asserts
   idempotency (per-schema no-duplicate-`(message_id, handler_name)` inbox apply + no over-shipped /
   over-reserved line), per-aggregate ordering (the forbidden end-states: shipped-and-cancelled, or a
   `completed` saga with an unshipped line), and empty-DLT (Kafka AdminClient — every `*.dlt` /
   `*.dlt.parked` topic empty). Verified live (50-user run + standalone finale, all six green). The
   one *remaining* sub-step is the active **force-rebalance-mid-run** variant (restart a consumer
   during the load, then re-assert) — the assertion is wired, the provocation is operational.
3. **Exercise the supply side under live concurrent load — done (run green).** `OperationsDriver`
   (a standalone poller) discovers outstanding POs + released WOs by JDBC and posts the real
   goods-receipt / WO-operation-complete actions; `OrderToCashSimulation` takes a `-Dproducts` feed
   (`to-order-products.csv` = the buy-to-order carpet + make-to-order chest). Run in parallel
   (`README.md` → *Supply-side run*), the full `shortage → replenishment → PO/WO →
   goods-receipt/WO-completion → retry-reserve → ship` loop runs end-to-end. **Confirmed live:** a
   12-user to_order run drove 12 sagas to `completed` and 12 work orders to `completed` (3
   manufacturing-pegged chest trees + 9 purchasing-pegged carpets), all pegged replenishments
   `fulfilled`, all six §6 invariants green. *The live run earned its keep:* it surfaced that the
   make-to-order chest (`FG-CHEST-001`) was flipped to `to_order` in the seed but never given a
   routing — nor its `SA-FRAME-001` / `SA-PANEL-001` sub-assemblies — so every chest WO release
   threw `RoutingNotFoundException`, dead-lettered, and wedged the saga (caught precisely by the
   new convergence + empty-DLT invariants from item 2). Fixed by seeding the three missing routings
   (`northwood_erp_seed.sql`).
4. **Focused-probe matrix completed** (done). TC-PAY-FIRST (prepayment completion gate — ship
   refused until the up-front invoice is paid, then the order completes), TC-PARTIAL-SHIP (a 2-qty
   line shipped in halves: shipped_quantity folds 0→1→2, line status reserved→partially_shipped→
   shipped, never over-ships), and TC-SUPPLY-DUP (two concurrent full goods-receipts on a pegged PO
   line top up on-hand exactly once — verified 3× under the barrier race) all land in
   `ConcurrentRaceProbesTest`. The suite is now **7 probes, all green** (0 skipped).
5. **(High bar) Linearizability / model-based checking** — record the concurrent history and
   check it linearises against the saga's transition function + the conservation arithmetic
   (Jepsen-style), rather than only snapshotting the end state.
6. **(High bar) Fault injection** — broker kills, partitions, node/crash recovery, to test
   exactly-once *across failures*, not just under concurrency.

### 11.4 Non-goals (unchanged)

- **Not a throughput benchmark.** Latency/throughput are reported, never asserted. A dedicated
  perf-tuning exercise (capacity planning, GC tuning) is a separate effort.
- **Not chaos testing** in the *current* tiers. Network partitions / broker kills / node loss are
  the further tier item 6 above names — out of scope for the shipped tiers.
