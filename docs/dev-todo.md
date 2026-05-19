# dev-todo.md

Implementation backlog ordered by **priority and dependency**, top-to-bottom. The first item is the next thing to pick up unless a new conversation surfaces something more pressing. Items inside each section are also priority-ordered.

When a slice ships:
1. Append an entry to `dev-done.md` under a new `## YYYY-MM-DD — <title>` heading. Keep enough detail to read cold (what shipped, smoke-test result, follow-ups noted at the time).
2. Remove (or trim) the corresponding entry here. If the slice surfaced new follow-ups, drop them into the appropriate section, ranked alongside existing items.

Section numbers are stable historical anchors referenced from `dev-done.md` (~300 cross-refs). When a slice ships, remove it but **do not renumber** surviving siblings — gaps in the sequence are deliberate.

---

## 1D. Observability — LGTM stack + SPA trace integration (PLANNED 2026-05-13, not started)

Northwood has Spring Boot Actuator on every service + both BFFs today, plus a persistent audit log and the SPA Saga Console / Event Log for cross-service visibility. What's missing: **distributed tracing** (the causal-chain view that complements the event drawer's *what happened* view), **metrics** (the *is it healthy* view), and **log aggregation** (one query surface across 7 services).

Design discussion + scope decisions captured 2026-05-13. Audience = **both** (Phase 1 demo storytelling, Phase 2 ops). Stack = **LGTM** (Loki / Grafana / Tempo / Prometheus — all run cheap in docker-compose, all under Grafana). SPA depth = **trace IDs surfaced in Event Log + Saga Console** with click-through to Grafana Tempo explore.

Two open decisions noted at plan time:
- Sampling rate: 100% under `dev` profile (rich demo data), 1-10% the typical prod posture — pin at implementation time.
- Custom-metrics scope: planned **technical-flavour** gauges (outbox lag, saga state distribution, event throughput) since "Business-domain metrics + custom dashboards" wasn't picked in scope. Revisit if a business-KPI panel becomes part of the demo narrative.

### 1D Phase 1 — showcase (sequential where noted; otherwise parallelizable)

| Slice | Title | Depends on | Headline change |
|---|---|---|---|
| **1D.1** | Service instrumentation (shared module) | 1D.0 (shipped) | `shared/pom.xml` adds `micrometer-registry-prometheus`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, `logstash-logback-encoder`, `loki-logback-appender`. Expose `prometheus` + `health` + `metrics` Actuator endpoints. OTLP exporter → `http://localhost:4317`. `logback-spring.xml` with JSON stdout + Loki appender + `%X{traceId}` / `%X{spanId}` from MDC. Each service confirms `spring.application.name`. |
| **1D.2** | Cross-service trace propagation | 1D.1 | Micrometer Tracing's Kafka instrumentation auto-handles `traceparent` headers once 1D.1 lands. OTel auto-instruments Spring's `RestClient` (saga aggregator polling, ERP BFF). `OutboxPublisher` also stamps `traceparent` into `EventEnvelope.headers` so the SPA can read it without a separate JOIN. Verify: drive place-order, one trace tree spans sales → inventory → manufacturing → finance. |
| **1D.3** | Schema: trace_id columns | — (independent) | One Liquibase changeset per table. `shared.audit_entry` gains `trace_id VARCHAR(32)` populated from current span. Each saga state table (`sales_order_fulfilment_saga`, `make_to_order_saga`, `purchase_to_pay_saga`) gains `trace_id` on row creation, preserved across transitions. Enables "give me the trace of this saga" via single column lookup. |
| **1D.4** | SPA integration | 1D.2 | `EventsAggregatorController.EventRow` adds `traceparent`. `demo-web-ui/EventStreamContext` types pick it up. `EventLog.tsx` + `SagaConsole.tsx` each render a small `↗ trace` affordance per row → opens Grafana Tempo Explore at that trace. Grafana itself stays off-BFF on `:3000` (no BFF route needed). |
| **1D.5** | Curated showcase dashboard | 1D.1 + 1D.2 + 1D.3 | One Grafana board, three rows. **Row 1** service health (up gauges, JVM heap, HTTP RPS). **Row 2** bus health — `outbox.pending` per service (custom Micrometer gauge), event throughput per Kafka topic, saga state distribution (counter per `state` per saga type). **Row 3** "a placed order's journey" — TraceQL panel `{name=~"POST /api/sales-orders"}`, click span → Loki logs for that span. |

### 1D Phase 2 — operational depth (deferred; resume after Phase 1 lands)

- **2.1 Alertmanager** wired to Slack/email. Sample rules: outbox stuck (pending > N for > M min), saga stuck in non-terminal state > N min, error-rate spike.
- **2.2 SLO panels** — 99% of events processed within 5s; 99% of saga transitions within 30s.
- **2.3 Health probes** — separate liveness / readiness / startup (Spring Boot Actuator groups) so future k8s deployment gets correct probes.
- **2.4 Exemplars** on latency histograms — click a slow bucket to jump to a trace from that bucket.
- **2.5 Loki labels** — promote `aggregateId`, `sagaId`, `eventType` from MDC into Loki labels so log queries like `{service="sales"} | json | sagaId="..."` work.

### Estimate

Phase 1: ~3-5 working sessions depending on dashboard polish. Phase 2 can be paused indefinitely — the Phase 1 foundation supports it but nothing in Phase 2 is story-critical.

### Why not now

Logged 2026-05-13 but **not started** — capturing the design while it's fresh, picking up after the next demo-narrative beat. Pull forward when the audience question "how do you debug across 7 services?" lands in a real demo.

---

## 1E. GitHub publish prep — remaining items (PLANNED 2026-05-13, foundation shipped)

Foundation pieces (LICENSE, .gitignore extension, secrets sweep) landed 2026-05-13 — see `dev-done.md`. Remaining items deferred so the user can decide tone/voice on the README themselves.

### Decisions locked 2026-05-13

- **License**: Apache 2.0 (Copyright 2026 Chi Liu).
- **Repo name**: `northwood-erp`.
- **Default branch**: `main` (already).

### 1E.1 README polish for public audience

Current `README.md` is project-internal — assumes the reader is already inside the codebase. Public-facing rewrite needs:

- **One-line elevator pitch** at the top: something like *"Event-driven microservices ERP architecture showcase — Spring Boot 4, 7 services with sagas + outbox/inbox, BFFs, two React/Vite demo SPAs."*
- **Requirements** stated explicitly: JDK 21, Docker (Postgres 17 + Kafka 4.1.2 + Keycloak), Node 20+, Maven 3.9+, Windows / macOS / Linux supported.
- **Screenshot or short GIF** of the Saga Console + Event Log + a curated demo flow. Visual cue carries more than prose for an architecture demo. (Could be in `docs/screenshots/` referenced relatively, or hosted on a GitHub Pages branch.)
- **"Demo credentials" disclosure section** listing the four items from the secrets sweep (Keycloak BFF secret, 13 user passwords, 7 service DB passwords, demo BFF bypass token) with the env-var override for each. Single most important addition for any consumer who might try to deploy this for real.
- **"Where to read next" pointers** to `CLAUDE.md`, `docs/demo-script.md`, `docs/architecture.md`, `docs/conventions.md` — the existing internal docs are useful to outside readers too, just need a roadmap to them.
- Keep the existing repository-structure tree (`README.md:10-30`-ish); it's already concise and useful for orientation.

### 1E.2 GitHub Actions CI workflow

`.github/workflows/build.yml` running on push + PR. ~30 lines. Two jobs in one file:

- **`backend`**: `actions/setup-java@v4` (Temurin 21), Maven cache, `mvn -B clean verify -DskipITs` (skip Testcontainers ITs by default since they need Docker-in-Docker; can be opt-in via workflow_dispatch input).
- **`frontend`**: `actions/setup-node@v4` (Node 20), npm cache, `npm ci && npm run build` for each of `demo-web-ui` and `erp-web-ui`.

Status badge in README header once the workflow lands. Skip Docker-based smoke for now — the docker-compose-up-then-boot path is hard to capture in standard GitHub-hosted runners without rearchitecture.

### 1E.3 GitHub-side metadata (paste in via the GitHub UI when creating the repo)

Not in-repo, but worth capturing the wording:

- **Description**: `Event-driven microservices ERP architecture showcase — Spring Boot 4, sagas, outbox/inbox, BFFs, React/Vite demo SPAs.`
- **Topics / tags**: `spring-boot`, `microservices`, `saga-pattern`, `outbox-pattern`, `event-driven-architecture`, `kafka`, `postgresql`, `domain-driven-design`, `hexagonal-architecture`, `java-21`, `react`, `vite`.
- **Default branch**: `main` (already).
- **Branch protection** on `main`: require status checks (the CI workflow above) once it lands.
- **Discussions / Issues**: enable Issues; leave Discussions off unless you want a community channel.

### Out of scope (deferred indefinitely; signal "open to outside contributors", which may or may not be wanted)

- `CONTRIBUTING.md` — not needed for a personal showcase.
- `SECURITY.md` — overkill for a demo.
- `CODE_OF_CONDUCT.md` — not needed unless you want a community.
- Demo video / hosted GitHub Pages tour — nice if time permits, but optional.

### Why not now

User wants to drive the README's tone and voice themselves (it's a public-facing artifact, more like marketing copy than internal docs). CI is mechanical and can land any time.

---

## 1F. Event-flow consumer coverage — deferred set (2026-05-14 audit)

All actionable items (§1F.1 – §1F.6) shipped 2026-05-14 / 2026-05-15. The deferred set below is documented as "considered, not pulled forward unless a trigger surfaces":

- **Customer master events** (`CustomerRegistered`, `CustomerNameChanged`, `CustomerAddressChanged`, `CustomerContactChanged`) — downstream views snapshot customer name from event payloads at order time, which is *historically correct*. Only a real gap if a UI requirement says "show current customer name on past orders".
- **`ManufacturingDispatched` → production-planning** — rejected lines (no BOM, not manufactured) generate dispatch outcomes but no work orders, so the planning board has nothing to update. Pull forward only if a manufacturing-rejection-rate widget is requested.
- **`WorkOrderCancelled` → finance** — no WIP cost is posted to GL today; nothing to reverse. Becomes real if WIP capitalisation lands (currently §3-adjacent, low priority).
- **`OperationCompleted` → ATP** — could progressively release planned quantity per completed operation, but current ATP waits for full `WorkOrderManufacturingCompleted`. Precision tradeoff, not a correctness gap.
- **`SalesPriceChanged` → reporting** — could feed a price-trend dashboard, but the demo doesn't require it.

---

## 1G.5 erp-web-ui — Story 7.1 scenario runner (parked)

Every individual mutation exists across the persona pages, but there's no scripted-scenario runner like `demo-web-ui`'s `ScenarioRunnerModal`. An operator has to navigate 5–6 pages in order (Sarah places order → Linda completes ops → Mike posts shipment → Olivia processes payment, etc.). The "watch all three sagas march in lockstep" framing is weaker than demo-web-ui's because no orchestrator pauses on saga state.

Scope: port the scenario runner shell from `demo-web-ui/src/scenarios/` to `erp-web-ui/src/scenarios/`, redefine step definitions in terms of `erp-web-ui` routes (e.g., navigate to `/sales-orders/new` then `/work-orders/{id}` then `/shipments/new`), and add a scenarios menu entry. Not story-blocking — every step works manually today.

Pull forward only if a public-facing audience wants the orchestrated walkthrough from the operational SPA too; the per-persona forms already work manually.

---

## 2. Polish on shipped slices

<!-- §2.0 fully shipped 2026-05-19 — see dev-done.md for the per-bucket entries. Convention captured in docs/conventions.md *Aggregate enumerated fields* + CLAUDE.md summary line. -->

### 2.3 Soft-cancel WIP path

**From:** §1.1 cancel-order slice 2026-05-06 (which shipped hard-cancel). Today a cancel arriving during `manufacturing_in_progress` immediately flips the WO to `cancelled` regardless of operation progress — WIP is written off. A more realistic ERP would let in-progress WOs finish then scrap the produced finished goods to a write-off bucket.

Scope:
- Decision: hard vs soft per WO based on a configurable threshold (e.g. operations-completed % > 50%)? Or a runtime flag on the cancel command?
- New events: `manufacturing.WorkOrderScrappedAfterCancel` (post-completion variant), inventory's WIP write-off.
- Finance hookup: when scrapped, post a write-off journal (Dr 5500 Write-off / Cr 1220 FG Inventory).

Wire into demo only after a clear narrative emerges — the showcase value of soft-cancel over hard-cancel is small and adds significant complexity.

### 2.4 CurrencyConverter depth

Today the converter handles same-currency pass-through, inverse-rate fallback, and now `GET /api/exchange-rate?from=…&to=…&date=…` (shipped §3 Slice E 2026-05-06). Remaining depth:

- **Scheduled rate importer** — today rates are inserted manually for a few currency pairs. `@Scheduled` task fetching from an external feed would let the demo simulate a daily rate close.
- **Triangulation through a base currency** — out of scope today (schema doesn't model a base currency); listed in §4 below.

### 2.6 Smoke-test gaps that need a running stack

Most of the original §2.6 list moved into §2.5.1's harness test targets (cancel-order, multi-receipt, deeper sub-assembly recursion, setPriority — see `dev-done.md` cross-references). Only items that genuinely need the running Kafka + Postgres + 7-service stack remain:

- **Sales fulfilment saga cross-partition race fix** (2026-05-05) — concurrent execution against partitioned Kafka; not reproducible in a synchronous in-memory harness. Existing happy-path multi-line test passes the regression by virtue of `expectedWorkOrderCount` being set correctly, so the gap is "no targeted assertion that the race specifically can't recur." Capture for the next end-to-end run; would need a deliberately-induced race (e.g. duplicate WorkOrderCreated emission with a 100ms delay).

### 2.8 Pricing split — Slice E (cross-currency BoM rollup)

Slices A–D shipped 2026-05-07 / 08 (split events, finance standard-cost projection, manufacturing-owned materials-cost rollup, BoM-walk + recursive parent recompute). See `dev-done.md`.

**Slice E (deferred — pull forward only if the cross-currency throw fires in the demo dataset)** — wire `CurrencyConverter` into the BoM rollup so multi-currency component prices roll up to a target currency.

### 2.15 Re-enable Liquibase once the schema stabilises

Liquibase was disabled across all 7 services on 2026-05-19 after a stale-volume boot failure (see `dev-done.md` for the consolidation slice). The 21 pre-existing changesets were folded into `db/northwood_erp.sql` and removed; `northwood.liquibase.enabled: false` is set in every service's `application.yml`; each master changelog is empty.

The disable is deliberate showcase-time hygiene — every slice that ships a structural change rebakes the baseline, the dev workflow is `docker compose down -v`, and the changeset-on-stale-volume failure mode (the one that triggered this) is a recurring loss.

Re-enable when:
- Baseline `northwood_erp.sql` is no longer changing meaningfully (no more pending structural slices that would force a rebake).
- Production-style deploys are on the horizon (`docker compose down -v` stops being acceptable; preserving data across schema changes becomes load-bearing).
- A migration story for the existing demo dataset is in place (Liquibase changesets must work alongside whatever data-migration approach we adopt).

Re-enable steps:
1. Flip `northwood.liquibase.enabled: true` in every service's `application.yml` (sales, inventory, manufacturing, purchasing, product, finance, reporting).
2. Update the comment block in each `db/changelog/db.changelog-master.yaml` (and remove the "currently empty" framing).
3. Verify on a fresh-volume boot that the empty changelogs no-op cleanly against the baseline.
4. Future schema changes follow the original workflow: drop a `.sql` file in the service's `changes/` dir + add an `include` to its master.

### 2.14 Kafka topic partitions — pre-declare with configurable counts

Today every event topic (`<service>.events` + matching `<topic>.dlt`) is auto-created on first publish (docker-compose `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`) with Kafka's default `num.partitions=1` — no `KAFKA_NUM_PARTITIONS` override, no `NewTopic` / `KafkaAdmin` bean, no `partitions:` setting in any `application-kafka.yml`. Means each consumer group has at most one active consumer per topic and the §2.6 cross-partition race regression is un-exercisable until partitions > 1.

Scope: flip `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` in docker-compose, add `NewTopic` beans in `shared/.../kafka` that read partition counts from `northwood.kafka.topics.<name>.partitions` (default 1 for the showcase, override to 3+ when exercising the partition-race test). Cover the 6 event topics + their DLT companions. Spring's `KafkaAdmin` creates the lot on service startup if missing. Keep RF=1 — single-broker constraint stays.

Audit items to clear before bumping any topic past 1 partition (full design in `docs/messaging-design.md` → *Hazards when scaling past 1 partition*):

1. **DLT partition count must match source.** The error-handler recoverer pins `record.partition()` (`KafkaMessagingAutoConfiguration.java:93`); mismatched counts cause poison-pill quarantine failure. Pre-declare both source + DLT.
2. **Verify saga concurrent-transition safety against a real broker.** Design is correct (`SELECT ... FOR UPDATE SKIP LOCKED` + optimistic `version` on `SagaPort`), but the synchronous test harness can't exercise the race. Need an integration-test target against a multi-partition broker.
3. **Audit projection write patterns for read-modify-write.** Atomic SQL increment is safe; load-process-write isn't. Candidates: `JdbcProductionPlanningProjection`, `JdbcProductCardProjection`, `JdbcStockBalanceWriter`.
4. **Re-test saga-prerequisite parking under realistic broker delay.** Multi-partition makes the "consequence event arrives before prerequisite saga row exists" path the common case, not the exception.
5. **Document topic-pre-declaration in the new-service checklist.** After auto-create flips off, new services must declare their topics or first publish throws `UnknownTopicOrPartitionException`.

### 2.13 Saga lease TTL + retry backoff → `@Value`-driven config

Three saga managers (`JdbcSalesOrderFulfilmentSagaManager:63`, `JdbcMakeToOrderSagaManager:43`, `JdbcPurchaseToPaySagaManager:37`) hardcode `Duration.ofSeconds(30)` lease TTL + `Duration.ofSeconds(15)` retry backoff. Triple-duplication of operational policy values; identified during §2.0.j as a candidate for constant extraction but better addressed as `@Value` config (matches the existing pattern for `northwood.saga.poll-interval` and `northwood.finance.match.priceTolerancePercent`). Two new property keys (e.g. `northwood.saga.lease-ttl-seconds`, `northwood.saga.retry-backoff-seconds`) with the current values as defaults; document in each saga manager Javadoc.

### 2.12 Role meta-annotations for `warehouse_manager`, `auditor`, `sysadmin`

The 2026-05-13 `@PreAuthorize` → `@RequireXxx` sweep created annotations under `shared/api/security/` for the 10 realm roles that gate actual endpoints today. The other 3 realm roles defined in `db/keycloak/northwood-realm.json` — `warehouse_manager` (force-release reservations, post stock adjustments), `auditor` (read-only everywhere), `sysadmin` (Keycloak realm admin only) — don't have annotations because no endpoint gates on them today. Scaffold matching `@RequireWarehouseManager` / `@RequireAuditor` / `@RequireSysadmin` when the first endpoint needs them.

---

## 3. Low priority — explicitly deferred (skip unless asked)

Do not pull these forward unless explicitly asked.

### 3.1 Multi-currency GL consolidation

User direction 2026-05-04. The architecture is in place (`Money` + `CurrencyConverter` + per-header rate snapshots), and the showcase runs single-currency end-to-end. Pull forward only when the audience asks about FX consolidation specifically.

### 3.2 Customer credit notes / refunds

User direction 2026-05-04. Forward AR (invoice → payment → settled) covers the showcase happy path. Reversal flows aren't on the critical path. Infrastructure (journal reversal, multi-allocation payments) is in place — adding a `CreditNote` aggregate + reversal-of-customer-invoice flow would be additive.

### 3.3 GST tax-account split

User direction 2026-05-04. Current journals fold tax-inclusive totals into COGS/Revenue and post cleanly. Splitting GST-input (1300 GRNI exists) / GST-output to dedicated accounts is accounting-correctness, not demo-blocking.

### 3.4 BOM authoring UI

User direction 2026-05-06 — explicitly low-priority during the §1 Security + UI slice. **Read-only tree view shipped in both SPAs** — `erp-web-ui/src/routes/manufacturing/Boms.tsx` (Linda) and `demo-web-ui/src/routes/Boms.tsx` (Emma) since 2026-05-13. What's still deferred is the authoring half: create draft, add/remove lines, drag-reorder, run cycle detection on save, flip draft → active. Backend authoring path is fully wired (`BomService` + 4 REST endpoints on `BomController`); the demo can use REST + curl until the editor UI lands. Pull forward if a planning-tool angle becomes part of the showcase narrative.

---

## 4. Out of scope (captured for completeness only)

These were considered and explicitly rejected; here so a future reader doesn't re-discover them and propose them as new work.

- **Inventory revaluation, average-cost rolling, FIFO/LIFO** — out of scope for the showcase. The `unitCost` from each receipt is taken at face value and never reconciled.
- **Reversal-of-reversal** — schema rejects (reversed entries are immutable). If business needs to "undo a reversal" it would post a new entry that re-applies the original's effect, not modify the existing chain.
- **Triangulation in CurrencyConverter** — schema doesn't model a base currency explicitly; out of scope until that decision is made.
- **`product_planning` / `product_pricing` sub-tables on product master** — locked 2026-05-04: stay with single columns going forward. Revisit only if a future facet has a multi-row shape (e.g. an approved-vendor list, which is genuinely a child collection).
