# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repo. Detail notes live under `docs/`; load them when the topic matches.

## Stack

- Java 21, Maven multi-module
- Spring Boot 4.0.5 (Spring Framework 7, Jakarta EE 11)
- Spring Data JDBC (deliberately not JPA — `docs/architecture.md`)
- PostgreSQL 17, Liquibase migrations (manually wired — `docs/persistence.md`), Testcontainers for integration tests
- Jackson 3 (`tools.jackson.*`), not the classic `com.fasterxml.jackson.*`
- springdoc-openapi 3.x — OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui/index.html` per service (e.g. http://localhost:8081/swagger-ui/index.html for product)

## Common commands

PowerShell on Windows. Run from repo root.

```powershell
mvn clean install -DskipTests                       # build all modules
docker compose up -d                                # postgres + kafka + keycloak
mvn -pl product-service spring-boot:run             # run a single service
mvn -pl product-service test                        # test a single service
mvn -pl product-service test -Dtest=ProductApplicationTests#main_methodIsCallable
docker compose down -v                              # nuke all infra + volumes
```

Service ports: product 8081, sales 8082, … reporting 8087. To run more than one service, open one terminal per service or use IntelliJ run configurations. Run with `SPRING_PROFILES_ACTIVE=kafka` for any cross-service flow (the outbox publisher and Kafka consumer wiring are gated on that profile).

## Hard architectural invariants

### Schema-per-service in one PostgreSQL database, ready to split

Treat as the project's load-bearing invariant — the database-per-service migration is a config change, not a refactor.

- **Each service has its own connection pool** authenticated as its own `<service>_service` PostgreSQL role.
- **Each pool sets `search_path = <service>, shared` on connection** (`spring.datasource.hikari.connection-init-sql`). A typo'd cross-schema reference fails at runtime — do not "fix" by qualifying the table name with another schema.
- **Liquibase is per-service.** Each service has its own master changelog + its own `databasechangelog` / `databasechangeloglock` in its own schema. `db/northwood_erp.sql` is the baseline; master changelogs start empty. New schema work → `<service>/src/main/resources/db/changelog/changes/<date>-<description>.sql` referenced from the master with `include:`.
- **Spring Boot 4.0.5 does not ship `LiquibaseAutoConfiguration`.** `shared/.../infrastructure/db/LiquibaseConfig.java` publishes `SpringLiquibase` manually via `@AutoConfiguration`.
- **No physical FKs cross schemas.** Cross-context relationships are plain UUIDs maintained by event projection.
- **No code references another service's schema by name.** Role grants enforce at runtime; treat as a code-review rule too.
- **The only inter-service contract is the outbox.** No "quick REST endpoint" between services. Cross-context interaction goes through events.

When the database eventually splits, the only file that changes per service is `application.yml` (the `spring.datasource.url`).

### Hexagonal layering — the 4-way rule

| Layer | May import from | May NOT import from |
|---|---|---|
| `api/`            | `application/` only                                                  | `domain/`, `infrastructure/` |
| `application/`    | `domain/`, other `application/`, `shared-kernel`, `shared.application` | `api/`, `infrastructure/` |
| `domain/`         | `shared-kernel` only                                                  | `api/`, `application/`, `infrastructure/` |
| `infrastructure/` | `application/`, `domain/`, `shared-kernel`, `shared.*`               | `api/` (with documented `@AutoConfiguration` exception below) |

**Machine-checkable** (run from repo root):

```
Grep '^import com\.northwood\.\w+\.domain\.'         **/api/**/*.java          → zero matches
Grep '^import com\.northwood\.\w+\.infrastructure\.' **/api/**/*.java          → zero matches
Grep '^import com\.northwood\.\w+\.api\.'            **/application/**/*.java  → zero matches
Grep '^import com\.northwood\.\w+\.infrastructure\.' **/application/**/*.java  → zero matches
Grep '^import com\.northwood\.\w+\.api\.'            **/domain/**/*.java       → zero matches
Grep '^import com\.northwood\.\w+\.application\.'    **/domain/**/*.java       → zero matches
Grep '^import com\.northwood\.\w+\.infrastructure\.' **/domain/**/*.java       → zero matches
Grep '^import com\.northwood\.\w+\.api\.'            **/infrastructure/**/*.java → exactly one match (the AuditAutoConfiguration exception)
Grep 'JdbcTemplate'                                  **/application/**/*.java  → zero matches
```

Any new match is a code-review fail.

**Documented exception:** `@AutoConfiguration`-annotated classes are layer-spanning factories (like `WebMvcAutoConfiguration` / `DataSourceAutoConfiguration` in Spring itself). The shared module's `AuditAutoConfiguration` imports `shared.api.audit.AuditController` to register the controller as a `@Bean`. This is the **only** exception to `infrastructure/ → api/`. Regular `@Configuration` / `@Component` / `@Service` doing the same is still a fail.

Full why-each-direction-is-forbidden rationale + the `application/dto/` View+Command pattern → `docs/conventions.md`.

### `application/` must not touch `JdbcTemplate` directly

JDBC lives in `infrastructure/persistence/` only. The grep above must return zero hits. An `application/` class whose constructor lists `JdbcTemplate jdbc` is a code-review fail — push it down. The standard split is interface in `application/` + `Jdbc*` impl in `infrastructure/persistence/`.

Every JDBC call in `*.application.*` belongs in one of:
1. A `*Projection` (in `application/inbox/`) — inbox-event-driven write to a read-model row.
2. A `*Repository` / `*QueryPort` / `*Lookup` — see vocabulary table below.
3. An aggregate method drained via `repository.save(...)` — when the change is a domain mutation that emits an event via `pendingEvents`.
4. `OutboxPort.appendPending(OutboxRow.pending(...))` — saga-side observation with no aggregate to mutate.

### Controllers (`api/`) must depend only on `application/`

Twin of the JdbcTemplate ban. Zero `import com.northwood.<service>.domain.*` in any file under `api/`. The application layer is the only seam between API and the rest of the system.

**Controllers do NOT inject or import:**
- Domain aggregates, domain VOs, domain identity VOs (services take raw `UUID` and wrap internally).
- Domain exceptions in `@ExceptionHandler` — wrap with an application-layer exception.
- `*Repository`, `*Projection`, `JdbcTemplate`, or anything from `infrastructure/`.

**Acceptable in a controller:**
- Application service classes; `*QueryPort` / `*Lookup` whose interfaces live in `application/`.
- `application/dto/*View` / `*Command` records (these are the wire format unless asymmetric — YAGNI default, no `api/dto/*Response` mirror needed for 1:1 shapes).
- `api/dto/*` records only when wire shape genuinely diverges.

Full do-not-import list + the View / Command / exception-wrapping patterns → `docs/conventions.md`.

## Naming summary

Port / repository / lookup vocabulary (full rules in `docs/conventions.md`):

| Role | Interface | Concrete impl |
|---|---|---|
| Infrastructure machinery (inbox/outbox/saga) | `*Port` | `Jdbc*Adapter` |
| Domain aggregate read+write (DDD Repository pattern) | `*Repository` | `Jdbc*Repository` |
| CQRS read-side projection (whole rows, lists) | `*QueryPort` | `Jdbc*QueryPort` |
| Narrow operational value lookup (single method, e.g. `findUnitPrice`) | `*Lookup` | `Jdbc*Lookup` |
| Inbox-event-driven write to a read model | `*Projection` (in `application/inbox/`) | `Jdbc*Projection` (in `infrastructure/persistence/`) |

**`*Repository` is reserved for DDD aggregate roots.** Every `*Repository` interface in `domain/` must have a sibling aggregate root file in the same package that declares `public static final String AGGREGATE_TYPE` and is the integrity boundary for at least one invariant. Mutating aggregates drain `pendingEvents` to the outbox at `save()`; event-less write-once aggregates (e.g. `finance.JournalEntry`, balance carried by a DB trigger) are permitted. A row-level write port without a backing aggregate is a code-review fail — either promote the aggregate, or pick a different suffix. Detail + rationale: `docs/conventions.md` → *No `*Repository` without an aggregate*.

**Deltas get aggregates, totals get projections.** The prior question (*what makes a concept deserve an aggregate at all?*) has one answer in this codebase: a concept gets an aggregate when it emits its own delta — a fact with identity, lifecycle, and downstream consumers. Running sums of those deltas (e.g. `stock_balance`, gl-account balance, `customer_invoice_header.paid_amount`) and snapshot caches of facts owned upstream (`sales.product_card`, `manufacturing.product_card`, etc.) get projection-shaped ports (`*Projection` / `*Writer` / `*Lookup` / `*QueryPort`) instead. Promoting a total to a `*Repository`+aggregate makes it possible for the total to diverge from the facts that produced it — the cardinal sin double-entry accounting was invented to prevent (Pacioli, 1494). Full framework + four-category table + promotion criteria + conceptual lineage (Pacioli → event sourcing → DDD+outbox): `docs/conventions.md` → *Aggregate vs projection — deltas get aggregates, totals and snapshot projections get projection ports*.

**Instance-field name = full aggregate name in plural** (no abbreviations, no generic `repository` / `repo` / `lookup`): `salesOrders` not `orders`, `supplierInvoices` not `invoices`, `productCards` not `cards`. Detail + rationale: `docs/conventions.md`.

**Aggregate enumerated fields = nested enum with `dbValue()` / `fromDb()`.** Status, type, kind, mode, source, match, method — every enumerated column on an aggregate table is a nested enum carrying its lowercase wire-format via `dbValue()`. Enum mirrors the schema CHECK set; schema-prep values (allowed by CHECK but not yet produced by Java) carry a `/** Schema-prep — not currently produced by Java. */` Javadoc tag. View DTOs convert `enum → String` via `.dbValue()` in their `from(...)` factory. Persistence reads via `Enum.fromDb(rs.getString(...))` and writes via `.dbValue()` — no `.name().toLowerCase()` ad-hoc conversions. Each aggregate keeps its own status field even when single-valued today. Detail + worked example: `docs/conventions.md` → *Aggregate enumerated fields*.

**Cross-service wire-format constants.** Cross-service consumers (different service) can't import another service's domain — schema-per-service rule. So the wire-format values get a compile-time anchor on the producer's `<service>-events` jar: on the event class itself for payload fields (`StockReserved.STATUS_RESERVED`, `PurchaseRequisitionCreated.SOURCE_TYPE_WORK_ORDER_SHORTAGE`), or in a dedicated `XxxStatuses` constants-holder class when the consumer needs to read/write the value but no single event payload field carries it (`WorkOrderStatuses.RELEASED` consumed by `reporting.JdbcProductionPlanningProjection`). Producer keeps using its nested enum's `dbValue()`; both paths produce the same string at runtime. SQL `WHERE`/`CASE` literals and `outbox.status = 'pending'` machinery are intentionally left as literals. Detail + the "did we cover it" test: `docs/conventions.md` → *Cross-service wire-format constants*.

## Class member ordering summary

- **All `static` fields above instance fields** — strict, no exceptions. Includes `private static final RowMapper<X>` lambdas in `Jdbc*` classes and SQL String constants in `Jdbc*QueryPort` classes. A `RowMapper` parked at the bottom of a repository is a code-review fail.
- **Nested types** (`static class`, `static enum`) above all fields. Static factory methods (e.g. `Customer.register`) sit near the top of the methods section, alongside the private constructor they wrap.
- **Static methods are not constrained** — group by what they do, not by their modifier.

Detail + the why: `docs/conventions.md` → *Class member ordering*.

## Schema naming summary

- **Singular table names.** `product`, `sales_order_line`, `outbox_message`. Detail: `docs/persistence.md`.
- **`_header` on master-detail parents only when child is `_line`.** Otherwise bare singular (`work_order` + `work_order_material`).
- **FK columns end in `_id`** and reference the singular table; `<header>_id` for line→header. PK on `_header` tables is `<header>_id`; PK on bare-singular tables is `<table>_id`.

## Document silent fallbacks

Any `.orElse(SENTINEL)` / null-coalescing-to-default needs: method-level Javadoc on emitter (trigger, value, rationale, tightening alternatives), cross-ref on the consumer that trusts it, log line when it fires (DEBUG for designed-tolerant, WARN for "shouldn't happen"), and a row in `docs/design-notes.md` under *Documented silent fallbacks*. Code review fails on undocumented or unindexed fallbacks. Detail: `docs/conventions.md`.

## Pointers

- **`docs/architecture.md`** — Module layout, the *event classes as navigation anchor* principle (three operational tests for cross-service traceability), events jars (with `EVENT_TYPE` and status-constant hosting rules), DDD service template, aggregate test rules, outbox/inbox shared-module wiring.
- **`docs/conventions.md`** — Full port/field-naming rules + `*Projection` sharp rule, hexagonal why-each-direction-is-forbidden, View/Command/Request patterns, exception wrapping (3 flavours), class-member-ordering rule, silent-fallback documentation rules, single-return + exhaustive-branch rule, PostgreSQL schema/table/column naming canonical statement.
- **`docs/sagas.md`** — The three saga state machines, reusable saga base, saga manager class shape (manager/worker/handler/emitter split with locations).
- **`docs/messaging-design.md`** — Outbox → Kafka → inbox flow, partition-key choice (`aggregateId` — the load-bearing design decision), ordering guarantees, DLT pinning, audit items for scaling past 1 partition. Load this when touching `KafkaEventPublisher`, `OutboxPublisher`, `SagaManager` concurrency, or any topic-config change (§2.14).
- **`docs/persistence.md`** — Schema conventions detail, money & exchange rates, reference data + seed UUIDs, Liquibase changeset idempotency rules + `northwood_erp.sql` baseline.
- **`docs/build-status.md`** — Per-service progress matrix and what's currently shipped vs skeleton.

## Spring Boot 4.0.5 project specifics

- **Jackson 3 is autoconfigured.** Imports must be `tools.jackson.databind.ObjectMapper` and `tools.jackson.core.JacksonException` (not `com.fasterxml.jackson.*`). `JacksonException` is unchecked.
- **Auto-config packages relocated** from `o.s.b.autoconfigure.<x>.*` → `o.s.b.<x>.autoconfigure.*` (e.g. `DataSourceAutoConfiguration` now at `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`). The `@AutoConfiguration` annotation itself is still at `org.springframework.boot.autoconfigure.AutoConfiguration`.
- **No Flyway/Liquibase auto-config in 4.0.5.** Hence the manual `LiquibaseConfig` in `shared.infrastructure.db`. Revisit if a future Boot version ships these.

Cross-project gotchas (CGLIB+final, Keycloak realm roles on access token only, Kafka single-broker RF, etc.) live in `~/.claude/notes/`.
