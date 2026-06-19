# load-test — concurrent order-to-cash, REST execution

The REST execution of the concurrent load test (`docs/concurrent-load-test.md` §7).
A Gatling Java-DSL simulation drives many concurrent, distinct-user order-to-cash flows
against a **live, seeded** Northwood stack; the shared JDBC `InvariantVerifier` asserts
the conservation invariants after the load drains.

> **Status — run end-to-end green against the live stack.** 200 concurrent distinct-user
> orders drive place → reserve → ship → invoice → pay; the post-run `InvariantVerifier`
> confirms zero oversell, balanced double-entry, and every fulfilment saga converged.
> This module is **not** a self-contained CI test — it needs the running multi-service
> stack. The CI-verified in-JVM property tier lives in `test-harness`
> (`o2c.OrderToCashPropertyTest`).

It is deliberately **outside the default Maven reactor** (parent POM `load-test` profile), so
`mvn verify` and CI never pull the Gatling toolchain. Build/run it explicitly with `-Pload-test`.

## What's here

| File | Role |
|---|---|
| `InvariantVerifier.java` | The shared assertion core — plain cross-schema JDBC. No oversell, double-entry balance, saga convergence (polled to a deadline, since payment → completed is async). Reused by the REST execution, the focused race probes, and the demo finale. Runnable standalone (`main`). |
| `KeycloakTokenFeeder.java` | Mints one bearer token per demo user (OAuth2 password grant) so the run drives genuinely different users (distinct `created_by`). |
| `OrderToCashSimulation.java` | Gatling scenario: place → poll-until-reserved → ship → poll-until-invoiced → pay, per user. Post-run invariant check in `after()`. |
| `provision-keycloak.ps1` | Idempotently adds the `northwood-loadtest` direct-grant client + N `user-N` load users (the order-to-cash role bundle) to the realm. The operational equivalent of the design doc's `Bootstrap` step. |
| `src/test/resources/products.csv` | The seeded SKUs under test (`productId,productSku,productName,unitCost`) — the in-stock to_stock finished goods. |

## Prerequisites for a real run

1. **Stack up**, with the demo seed and all services on the Kafka profile:
   ```powershell
   docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d
   # then run each service with SPRING_PROFILES_ACTIVE=kafka (one terminal per service,
   # or IntelliJ run configs — see CLAUDE.md "Common commands")
   ```
2. **Provision Keycloak** — the committed demo realm has only 13 single-role named users and
   no direct-grant client, neither of which can drive the load test. Add N numbered load users
   + a password-grant client:
   ```powershell
   ./provision-keycloak.ps1 -Users 200
   ```
3. **Ample stock** for the SKUs in `products.csv` so reservation succeeds at placement (this
   run targets the in-stock to_stock finished goods). To rule out reorder-point noise during a
   large run, bump on-hand once:
   ```powershell
   docker exec -i northwood-postgres psql -U postgres -d northwood_erp -c `
     "UPDATE inventory.stock_balance SET on_hand_quantity=100000 WHERE warehouse_id='00000000-0000-7000-8000-000000000020' AND product_id IN ('00000000-0000-7000-8000-000000000400','00000000-0000-7000-8000-000000000001','00000000-0000-7000-8000-000000000200');"
   ```

## Run

```powershell
mvn -Pload-test -pl load-test gatling:test `
  "-Dgatling.simulationClass=com.northwood.loadtest.OrderToCashSimulation" `
  "-Dusers=200" "-Dramp=40"
# Gatling HTML report: load-test/target/gatling/<sim>-<ts>/index.html
```

Tunables (system properties): `users` (= distinct provisioned users, also the VU count),
`ramp` (seconds), `poll` (per-step poll deadline), `converge` (post-run convergence deadline),
and the base URLs `sales.base` / `inventory.base` / `finance.base` / `keycloak.issuer` /
`jdbc.url`.

## Invariant verifier standalone (demo finale)

```powershell
mvn -Pload-test -pl load-test exec:java `
  "-Dexec.mainClass=com.northwood.loadtest.InvariantVerifier" `
  "-Dexec.classpathScope=test" `
  "-Djdbc.url=jdbc:postgresql://localhost:5432/northwood_erp" "-Djdbc.user=postgres" "-Djdbc.password=postgres"
```

## Resolved `TODO(live)` (settled against the running stack)

- **Customer-invoice lookup** — there is **no** server-side `?salesOrderHeaderId=` filter
  (`CustomerInvoiceController` only lists all / by-id). The harness fetches the full list and
  filters client-side by `salesOrderHeaderId` via a JSONPath predicate.
- **Saga-state exposure** — `SalesOrderView` exposes the order **status** fold (`open` →
  `reserved` → `shipped`), not the saga state. The scenario polls `status == "reserved"` to
  gate the ship step.
- **Ports** — sales 8082, inventory 8083, finance 8086 (confirmed in each `application.yml`).
- **`products.csv`** — populated with the seeded in-stock to_stock finished goods.
- **Auth** — `provision-keycloak.ps1` creates the direct-grant client + N users, each carrying
  the whole order-to-cash role bundle (sales_clerk + warehouse_clerk + accountant) so one
  virtual user can drive the end-to-end flow.

## Focused race probes — `ConcurrentRaceProbesTest`

Deliberate two-worker collisions on a single aggregate (`CyclicBarrier`-synchronised), each
asserting an exactly-once / no-half-state property against the live DB. Run:

```powershell
mvn -Pload-test -pl load-test test "-Dtest=ConcurrentRaceProbesTest"
```

- **TC-DOUBLE-PAY** (green) — two concurrent full customer payments on one invoice allocate
  exactly once; the second is rejected by `CHECK (paid_amount <= total_amount)` + the
  row-locking allocation trigger.
- **TC-DOUBLE-SHIP** / **TC-CANCEL-SHIP** (`@Disabled`) — these probes **found real races** and
  are quarantined pending fixes (filed in `dev-todo.md`): concurrent double-ship double-decrements
  `inventory.stock_balance.on_hand_quantity` (no synchronous over-ship guard in `ShipmentService`),
  and concurrent cancel-vs-ship can leave an order both shipped and cancelled (the
  `anyLineShipped()` cancel gate reads sales-local state that lags the async shipment event).
  The test bodies are kept as the executable spec; re-enable once the guards land.

## Out of scope here (the operations / supply tier)

The **supply-side replenishment driver** (goods receipt / work-order completion for
undersized-stock shortage paths) and the remaining focused cases (TC-PAY-FIRST, TC-PARTIAL-SHIP,
TC-SUPPLY-DUP). This module proves the conservation invariants hold under concurrent placement /
shipment / payment on the shared `stock_balance` and GL rows for the ample-stock path.
