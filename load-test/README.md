# load-test — concurrent order-to-cash, REST execution

The REST execution of the concurrent load test (`docs/concurrent-load-test.md` §5.3/§7).
A Gatling Java-DSL simulation drives many concurrent, distinct-user order-to-cash flows
against a **live, seeded** Northwood stack; the shared JDBC `InvariantVerifier` asserts
the conservation invariants after the load drains.

> **Status — scaffold, not a self-contained test.** This module is **compile-checked only**
> (`mvn -Pload-test -pl load-test test-compile`). It has **not** been run end-to-end — that
> requires a running multi-service stack, which is environment/Docker-heavy and outside the
> in-JVM tier (§5.1/§5.2). The in-JVM property suite that *is* CI-verified lives in
> `test-harness` (`OrderToCashConcurrentLoadPropertyTest`). Items that can only be settled
> against the running system are marked `TODO(live)` in the simulation.

It is deliberately **outside the default Maven reactor** (parent POM `load-test` profile), so
`mvn verify` and CI never pull the Gatling toolchain. Build/run it explicitly with `-Pload-test`.

## What's here

| File | Role |
|---|---|
| `InvariantVerifier.java` | The shared assertion core — plain cross-schema JDBC. No oversell, double-entry balance, saga convergence. Reused by every execution and the demo finale. Runnable standalone (`main`). |
| `KeycloakTokenFeeder.java` | Mints one bearer token per demo user (OAuth2 password grant) so the run drives genuinely different users (distinct `created_by`). |
| `OrderToCashSimulation.java` | Gatling scenario: place → wait-for-invoice → pay, per user. Post-run invariant check in `after()`. |

## Prerequisites for a real run

1. **Stack up**, with the demo seed and all services on the Kafka profile:
   ```powershell
   docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d
   # then run each service with SPRING_PROFILES_ACTIVE=kafka (one terminal per service,
   # or IntelliJ run configs — see CLAUDE.md "Common commands")
   ```
2. **Keycloak** reachable at the issuer URI with N demo users (`user-0 … user-{N-1}`) and a
   direct-grant client. The UI execution (§5.5) needs these too.
3. **`products.csv`** in `load-test/src/test/resources/` with the seeded SKUs under test:
   ```csv
   productId,productSku,productName
   <uuid-from-seed>,FG-TABLE,Dining Table
   ```
   `TODO(live)`: populate the UUIDs from `config/postgresql/northwood_erp_seed.sql`.

## Run

```powershell
mvn -Pload-test -pl load-test gatling:test `
  -Dgatling.simulationClass=com.northwood.loadtest.OrderToCashSimulation `
  -Dusers=200 -Dramp=120 `
  -Dsales.base=http://localhost:8082 -Dfinance.base=http://localhost:8086 `
  -Dkeycloak.issuer=http://localhost:8090/realms/northwood `
  -Djdbc.url=jdbc:postgresql://localhost:5432/northwood_erp
# Gatling HTML report: load-test/target/gatling/<sim>-<ts>/index.html
```

The shipment / goods-receipt / work-order-completion steps (the **operations driver**, §4) are
not in this customer scenario — drive them with a second low-rate scenario or a manual robot, so
placed orders reach the invoiced state the `pay` step waits on.

## Invariant verifier standalone (demo finale)

```powershell
mvn -Pload-test -pl load-test exec:java `
  -Dexec.mainClass=com.northwood.loadtest.InvariantVerifier `
  -Dexec.classpathScope=test `
  -Djdbc.url=jdbc:postgresql://localhost:5432/northwood_erp -Djdbc.user=postgres -Djdbc.password=postgres
```

## Remaining `TODO(live)` before a green run

- Confirm the **customer-invoice lookup** path/field (`GET /api/customer-invoices?salesOrderHeaderId=…` → `$[0].id`).
- Confirm `SalesOrderView` / saga-state exposure if polling order progress directly.
- Populate `products.csv` with real seeded product UUIDs.
- Add the operations-driver scenario (ship / goods-receipt / WO-completion).
