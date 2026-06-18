# web-ui-load-test — concurrent order-to-cash, Web-UI execution

The **Web-UI execution** of the concurrent load test (`docs/concurrent-load-test.md` §5) —
the *fidelity* run. Playwright drives N concurrent, **distinct-OIDC-user** browser sessions
through the **real** `erp-web-ui` SPA → ERP BFF → services path: each browser context logs into
Keycloak via the genuine OIDC authorization-code flow (tokens never reach the browser — the BFF
holds them), then places an order over the same network path the SPA itself uses.

It asserts the front-end / BFF concurrency-safety properties the Gatling REST run cannot reach:

- **Session isolation / no token bleed** — each context's `/api/me` reports *its own* username;
  the N identities are all distinct.
- **Distinct `created_by`** — each user places an order (HTTP 201) with a distinct id, so N
  distinct real identities flow end-to-end (verified server-side: `sales_order_header.created_by`
  = each user's `preferred_username`).

Deliberately bounded (§5: ~10–50 sessions) — contention is a backend property proven by the
Gatling run (`../load-test`); this proves the real front-end path drives the same correct flow
for concurrent, distinct, real users. The conservation invariants (§6) are the shared Java
`InvariantVerifier` (`../load-test`), reused as the demo finale.

## Prerequisites (LIVE)

1. Stack up + all services on the Kafka profile (see repo `CLAUDE.md`).
2. `../load-test/provision-keycloak.ps1 -Users <N>` — the `user-N` load users (they carry
   `sales_clerk`, so they can place orders) double as the browser personas.
3. The ERP BFF on `:8089` and the `erp-web-ui` Vite dev server on `:5174`:
   ```powershell
   cd ../erp-web-ui ; npm run dev      # serves :5174, proxies /api + /oauth2 to the BFF :8089
   ```

## Run

```powershell
npm install
npm run install-browser        # playwright install chromium (one-off)
$env:UI_USERS="5"; npm test     # 5 concurrent distinct-user browser sessions
```

Tunables (env): `UI_USERS` (sessions = distinct users), `UI_PASSWORD` (default `password`),
`SPA_BASE` (default `http://localhost:5174`), `HEADED=1` to watch the browser wall.

## Demo mode — the live finale

The same conservation verdict the REST run prints is the demo's payoff. After driving load
(REST and/or UI), run the shared verifier standalone against the live DB:

```powershell
cd ../load-test
mvn -Pload-test -pl load-test exec:java `
  "-Dexec.mainClass=com.northwood.loadtest.InvariantVerifier" "-Dexec.classpathScope=test" `
  "-Djdbc.url=jdbc:postgresql://localhost:5432/northwood_erp" "-Djdbc.user=postgres" "-Djdbc.password=postgres"
# → "✓ All load-test invariants hold (no oversell, ledger balances, sagas converged)."
```

The verifier reports the **actual** end state, so run it as the finale of a *fully-drained*
run (every order placed → shipped → paid), as the Gatling stress run leaves it. It correctly
prints `✗ Invariants violated` when sagas are still non-terminal — e.g. right after the focused
race probes (`../load-test` `ConcurrentRaceProbesTest`), which deliberately leave orders
parked at `supply_secured` (placed + reserved but never paid). That is the verifier doing its
job, not a defect: clear the probe orders (or re-run after a clean stress run) for the green
demo verdict.

For the full watchable demo, bring the LGTM observability tier up
(`docker compose ... -f docker-compose.observability.yml up -d`, see `docs/observability.md`),
open Grafana, run the Gatling stress run (`../load-test`) and/or this UI wall (`HEADED=1`) at a
watchable rate, then run the verifier as the green-verdict finale.

## What's verified vs not

- **Verified live (2026-06-19):** 5 concurrent distinct-user sessions log in via real Keycloak
  OIDC, place orders through the real SPA→BFF→sales path, session isolation holds, 5 distinct
  `created_by` landed. (`UI_USERS=5 npm test` → green.)
- **Scale knob:** `UI_USERS` up to ~50 per the design; not a backend stress lever (that's the
  REST run). The DOM-click order form is not driven (the spec uses the context's authenticated
  request, which is the SPA's exact network path); driving the form widgets is a fidelity nicety,
  not a correctness lever.
