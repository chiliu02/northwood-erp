# Redis Showcase for Northwood ERP

> A **design/plan doc** for introducing Redis to Northwood, grounded in the real module/package layout.
> Two parts: (1) distributed BFF sessions, (2) cache-aside on reporting read models with event-driven
> invalidation. Not yet implemented.

---

## Context — why Redis, why now

Redis is **not used anywhere in Northwood today** — this is a green-field addition. Two needs in the current
architecture map cleanly onto it, and the first is already an acknowledged gap.

From `docs/messaging.md` (known-gaps list):

> **BFF restart (non-messaging).** `erp-web-ui-bff` holds OIDC session/token state in-JVM, so a restart logs
> every user out (re-login; **Redis-backed sessions are deferred**).

So the project itself earmarks Redis for sessions. The second need — read-model caching on `reporting-service`
— follows naturally from the CQRS design: reporting serves hot, read-heavy projections that are updated by a
well-defined event stream, which is exactly what makes cache invalidation tractable.

**Scope (confirmed):** Part 1 (session-in-Redis) + Part 2 (reporting cache-aside).

---

# Part 1 — Distributed BFF sessions (Spring Session + Redis)

## The problem (real, documented)

Both browser-facing BFFs — `erp-web-ui-bff` (port 8089) and the new `erp-graphql-bff` (port 8088, see
`docs/graphql.md`) — run the OIDC authorization-code flow and keep the user's Keycloak tokens in server-side
JVM state. Consequences:

1. **Restart/redeploy a BFF → every user is logged out.**
2. **You can't run two BFF replicas** — there's no shared session, so a load-balanced second instance can't see
   the first's logins.

## The fix

Add `spring-session-data-redis` + `spring-boot-starter-data-redis` to each BFF. The `HttpSession` (and the
Spring Security `SecurityContext` it carries) moves to Redis; sessions now survive restarts and are shared
across replicas. `BffSecurityConfig` is unchanged.

## ⚠️ Gotcha — the OAuth2 *authorized client* is NOT in the session by default

`BffAccessTokenAccessor` (the piece that hands the Bearer token to `ProxyController`) reads the token via an
**`OAuth2AuthorizedClientService`**:

```java
// erp-web-ui-bff/.../security/BffAccessTokenAccessor.java
OAuth2AuthorizedClient client = clientService.loadAuthorizedClient(registrationId, principalName);
```

Spring Boot's default bean for that type is **`InMemoryOAuth2AuthorizedClientService`** — a JVM map, *not* the
HttpSession. So moving only the session to Redis would still leave the **access token** in per-instance memory:
after a restart the user appears authenticated (SecurityContext is in Redis) but the token lookup returns null,
and upstream calls fail. Two clean ways to fix it — pick one:

- **(Recommended, implemented) Switch to the session-backed repository — and register it explicitly.** Change
  `BffAccessTokenAccessor` to use `OAuth2AuthorizedClientRepository`, which stores the authorized client in the
  `HttpSession` — and therefore in Redis once Spring Session is on. The accessor is a singleton but every call
  runs on a request thread, so it resolves the current request from `RequestContextHolder` and reads
  `repo.loadAuthorizedClient(registrationId, authentication, request)` — the four callers keep their signatures.
  **The catch:** the *default* `OAuth2AuthorizedClientRepository` is **not** session-backed. Spring Boot
  auto-configures an `InMemoryOAuth2AuthorizedClientService`, and the default repository
  (`AuthenticatedPrincipalOAuth2AuthorizedClientRepository`) **delegates to that in-memory service for
  authenticated principals** — so just swapping the injected type would still leave the token in per-JVM memory.
  Fix it by registering a `HttpSessionOAuth2AuthorizedClientRepository` `@Bean` in `BffSecurityConfig`; that one
  bean makes *both* the login-time save and the accessor's read go through the session. (So `BffSecurityConfig`
  gains exactly that bean — it is otherwise unchanged.)
- **(Alternative) Redis-backed `OAuth2AuthorizedClientService`.** Provide a custom `OAuth2AuthorizedClientService`
  bean that persists to Redis. Keeps the accessor's current signature, but is more code than option 1.

This nuance is the credibility centerpiece of the session demo — "just add Spring Session" is the trap;
the token store is the part people miss.

## Demo

- **Restart resilience:** log in, `mvn spring-boot:run` restart the BFF, refresh — still logged in, upstream
  calls still carry the token.
- **Horizontal scale:** run two BFF instances (8089 + e.g. 8099) sharing one Redis; round-robin requests and
  show the session/token follow the user across instances.
- Inspect Redis directly: `KEYS spring:session:*` shows live sessions.

---

# Part 2 — Cache-aside on reporting read models, invalidated by Kafka

## Why reporting is the right target

`reporting-service` is the CQRS read side. Its structure makes cache-aside + invalidation a textbook fit:

| Layer | Package | Role |
|---|---|---|
| REST | `com.northwood.reporting.api.*Controller` | serve projections |
| Read port | `application.*QueryPort` (iface) → `infrastructure.persistence.Jdbc*QueryPort` | **where `@Cacheable` goes** |
| Write side | `application.inbox.**` handlers + `infrastructure.persistence.Jdbc*Projection` | **where eviction goes** |

The read path and the write path are already separated, and **every projection update arrives as a known event**
through the inbox handlers — so eviction has a precise, deterministic trigger.

## Where caching goes

Annotate the read methods on the JDBC query ports (Spring Cache + Redis cache manager):

- `JdbcAvailableToPromiseQueryPort`     → cache `atp`, key = `productId`
- `JdbcFinancialDashboardQueryPort`     → cache `financial-dashboard` (singleton-ish snapshot key)
- `JdbcSalesOrder360QueryPort`          → cache `sales-order-360`, key = `salesOrderHeaderId`

```java
@Cacheable(cacheNames = "atp", key = "#productId")
public AvailableToPromiseView byProduct(UUID productId) { ... }
```

Add a short **TTL per cache** (e.g. 30–60 s) as a safety net so any missed eviction self-heals — belt and
braces, not the primary correctness mechanism.

## Event-driven eviction (the hard, interesting half)

Reporting's inbox handlers are already grouped by read model — ATP and dashboard even have dedicated
subpackages. Each handler knows the entity it just changed, so it can evict the exact key:

| Cache | Evict from (existing handlers / writers) | Example trigger events |
|---|---|---|
| `atp` (by productId) | `application.inbox.atp.*` (`GoodsReceivedHandler`, `StockReservedHandler`, `RawMaterialsReservedHandler`, `ShipmentPostedHandler`, `WorkOrderCompletedHandler`, `PurchaseOrderCreatedHandler`, `ProductCreated/DiscontinuedHandler`, …) | stock/PO/WO events affecting availability |
| `financial-dashboard` | `application.inbox.dashboard.*` (`CustomerInvoiceCreatedHandler`, `CustomerPaymentReceivedHandler`, `SupplierPaymentMadeHandler`, `SalesOrderShippedHandler`, …) **and** the scheduled `infrastructure.dashboard.FinancialDashboardBalanceWorker` | invoice/payment/shipment events + periodic balance recompute |
| `sales-order-360` (by id) | the `SalesOrder360*Handler` family (`SalesOrderPlaced`, `…LineAdded/QuantityChanged/Removed`, `…StockReserved`, `…ReadyToShip`, `…Shipped`, `…Compensated`, …) | any event mutating that order's 360 view |

Mechanism: `@CacheEvict(cacheNames = "...", key = "...")` on the handler's `apply(...)`, **or** an explicit
`cache.evict(key)` call right after the projection write. Evict the **specific key**, not the whole cache.

```java
// in the inbox handler that just updated the ATP projection for a product
@CacheEvict(cacheNames = "atp", key = "#productId")
protected void apply(...) { /* projection write */ }
```

## Consistency note

Projection write and cache eviction must not leave a stale entry visible. Two safe orderings:
- Evict **after** the projection commit (a concurrent read in the gap re-reads fresh; the brief window is
  covered by the TTL backstop), or
- Use Spring's transaction-bound eviction (evict on after-commit) to avoid evicting then re-populating stale
  inside an uncommitted tx.
Either way, the **TTL is the backstop**, the event-driven evict is the primary path.

## Metrics (free win — observability stack already exists)

Northwood already runs Prometheus + Grafana. Spring Cache + Micrometer expose `cache.gets{result=hit|miss}`
and `cache.size` per cache name — drop a cache-hit-ratio panel onto the existing dashboards to *show* the cache
working, and to prove invalidation isn't over-evicting.

---

## Dependencies & config

**BFFs (`erp-web-ui-bff`, `erp-graphql-bff`):**
- `org.springframework.boot:spring-boot-starter-data-redis`
- `org.springframework.session:spring-session-data-redis`

**reporting-service:**
- `org.springframework.boot:spring-boot-starter-data-redis`
- `org.springframework.boot:spring-boot-starter-cache`

**Shared config (`application.yml`):**
```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  # BFFs only:
  session:
    store-type: redis
    timeout: 30m
  # reporting only:
  cache:
    type: redis
    redis:
      time-to-live: 60s        # TTL backstop
```

**Infra:** add a `redis:7` service to the dev `docker-compose` (alongside Postgres/Keycloak/Kafka), with a
healthcheck. One shared Redis is fine for the demo; in production sessions vs cache would typically be separate
logical DBs or instances.

---

## Touch points (files)

- **Session:** `erp-web-ui-bff/pom.xml`, `erp-graphql-bff/pom.xml` (deps); each `application.yml` (session +
  redis); `BffAccessTokenAccessor.java` in both BFFs (switch to `OAuth2AuthorizedClientRepository`, the gotcha
  above), plus a `HttpSessionOAuth2AuthorizedClientRepository` `@Bean` in `BffSecurityConfig` (the in-memory-service
  trap, the gotcha above) — `BffSecurityConfig` is otherwise unchanged.
- **Cache:** `reporting-service/pom.xml` (deps); `@EnableCaching` on `ReportingApplication`;
  `@Cacheable` on `Jdbc{AvailableToPromise,FinancialDashboard,SalesOrder360}QueryPort`;
  `@CacheEvict` (or explicit evict) in the matching `application/inbox/{atp,dashboard,…}` handlers and
  `FinancialDashboardBalanceWorker`.
- **Infra:** `docker-compose*.yml` (+ the seed compose) — add `redis`.

---

## Verification (end-to-end)

**Session:**
1. `docker compose up redis` (+ existing infra), start a BFF.
2. Log in via Keycloak; confirm `redis-cli KEYS "spring:session:*"` shows a session.
3. Restart the BFF → refresh the SPA → still logged in, upstream API calls succeed (token survived).
4. Start a second BFF instance on another port sharing the same Redis → requests to either instance work
   without re-login.

**Cache:**
1. Start `reporting-service` with Redis up.
2. `GET /api/atp/{productId}` twice → first is a `miss`, second a `hit` (`/actuator/metrics/cache.gets`);
   `redis-cli KEYS "atp::*"` shows the cached key.
3. Trigger an availability-changing event for that product (e.g. place/ship an order, or post a goods receipt)
   → confirm the `atp::{productId}` key is evicted and the next `GET` is a fresh `miss` reflecting new numbers.
4. Watch the cache-hit-ratio panel in Grafana under read load.

**Tests:** an `@SpringBootTest` slice with an embedded/Testcontainers Redis asserting (a) second read hits the
cache (mock the JDBC port, verify it's called once), and (b) the relevant inbox handler evicts the right key.

---

## Out of scope / deliberately avoided

- **Redis as an idempotency store.** Northwood already has a deliberate Postgres **inbox** pattern
  (`AbstractInboxHandler` / `inbox_message`) for exactly-once event handling — replacing a working design is a
  weak showcase, so we leave it alone.
- Caching the other projections (material-shortage, production-planning, PO-tracking, replenishment-history) —
  same pattern, add later if useful.
- Redis as the projection store itself (materializing read models into Redis instead of Postgres) — larger
  change than a cache-aside demo warrants.
- Separate Redis instances/DBs for session vs cache, Redis HA/Sentinel/Cluster — production hardening, not demo.
