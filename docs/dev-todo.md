# dev-todo.md

Implementation backlog ordered by **priority and dependency**, top-to-bottom. The first item is the next thing to pick up unless a new conversation surfaces something more pressing. Items inside each section are also priority-ordered.

When a slice ships:
1. Append an entry to `dev-done.md` under a new `## YYYY-MM-DD — <title>` heading. Keep enough detail to read cold (what shipped, smoke-test result, follow-ups noted at the time).
2. Remove (or trim) the corresponding entry here. If the slice surfaced new follow-ups, drop them into the appropriate section, ranked alongside existing items.

Section numbers are stable historical anchors referenced from `dev-done.md` (~300 cross-refs). When a slice ships, remove it but **do not renumber** surviving siblings — gaps in the sequence are deliberate.

---

## 1D. Observability — Phase 2 deferred set (Phase 1 shipped 2026-05-20)

§1D Phase 1 (LGTM stack + cross-service tracing + curated showcase dashboard) shipped across slices 1D.0 → 1D.5 on 2026-05-20 — see `dev-done.md`. Phase 2 below is what was deliberately deferred at plan time; pull forward when a real ops story lands.

- **2.1 Alertmanager** wired to Slack/email. Sample rules: outbox stuck (pending > N for > M min), saga stuck in non-terminal state > N min, error-rate spike.
- **2.2 SLO panels** — 99% of events processed within 5s; 99% of saga transitions within 30s.
- **2.3 Health probes** — separate liveness / readiness / startup (Spring Boot Actuator groups) so future k8s deployment gets correct probes.
- **2.4 Exemplars** on latency histograms — click a slow bucket to jump to a trace from that bucket.
- **2.5 Loki labels** — promote `aggregateId`, `sagaId`, `eventType` from MDC into Loki labels so log queries like `{service="sales"} | json | sagaId="..."` work.

### Why not now

Phase 1 closes the demo-story question "how do you debug across 7 services?". Phase 2 is the operational-depth story — alerts, SLOs, k8s-shape probes — which is only load-bearing once Northwood runs against real traffic. Resume when that's on the table.

---

## 1E. GitHub publish prep — remaining items (PLANNED 2026-05-13, foundation shipped)

Foundation pieces (LICENSE, .gitignore extension, secrets sweep) landed 2026-05-13; §1E.1 (README public-facing prose rewrite + Saga Console screenshot) landed 2026-05-21 — see `dev-done.md`. Remaining: CI workflow (§1E.2) and GitHub-side metadata (§1E.3).

### Decisions locked 2026-05-13

- **License**: Apache 2.0 (Copyright 2026 Chi Liu).
- **Repo name**: `northwood-erp`.
- **Default branch**: `main` (already).

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

## 1H. Error-response shape — deferred follow-up (§1H shipped 2026-05-20)

§1H Backend error-response shape + `DomainException` scaffolding shipped 2026-05-20 along with three follow-up tightening passes (hoist `code()` to marker bases → collapse bases into `AbstractDomainException` → re-introduce `CODE` constants on each concrete class). The remaining item below is deferred until a real consumer needs it.

### 1H.1 Build-time generated error-code catalog

Generate a single JSON artifact (e.g. `shared/build/error-codes.json`) by reflecting over every concrete subclass of `shared.application.exception.AbstractDomainException` at build time and emitting `{ code, marker, declaringClass, paramKeys[] }` per entry. Surfaces:

- **Typed TypeScript const for the SPAs** — `demo-web-ui/src/generated/errorCodes.ts` and `erp-web-ui/src/generated/errorCodes.ts`, generated alongside the JSON. Each `ErrorResponse.code` becomes a typed union; missing-bundle-key checks become compile-time.
- **Documentation surface** — Markdown table emitted to `docs/error-codes.generated.md` listing every code with its declaring class, HTTP status, and param keys.

The constraint that makes this worth automating: the catalog must never drift from the actual Java constants. A hand-maintained `error-codes.json` becomes a worse artifact than no artifact the first time it goes stale.

Pull forward when:
- The SPA needs **typed dispatch** on `code` (today both SPAs treat `code` as a localisation lookup key — plain string equality, no exhaustiveness check needed).
- A second locale lands and **missing-bundle-key bugs** become a real failure mode (§3.5 SPA i18n).
- A **third Java consumer** of the codes appears (BFF branching, an external integration, something not yet on the radar).

Skip indefinitely if the SPA never moves past "look up the code in the bundle, render English fallback if missing." The per-class `CODE` constants on the Java side already give producers + Java consumers a typed import path; the catalog only earns its keep when the *SPA* needs structured access.

---

## 2. Polish on shipped slices

<!-- §2.0 fully shipped 2026-05-19 — see dev-done.md for the per-bucket entries. Convention captured in docs/conventions.md *Aggregate enumerated fields* + CLAUDE.md summary line. -->

### 2.6 Smoke-test gaps that need a running stack

Most of the original §2.6 list moved into §2.5.1's harness test targets (cancel-order, multi-receipt, deeper sub-assembly recursion, setPriority — see `dev-done.md` cross-references). Only items that genuinely need the running Kafka + Postgres + 7-service stack remain:

- **Sales fulfilment saga cross-partition race fix** (2026-05-05) — concurrent execution against partitioned Kafka; not reproducible in a synchronous in-memory harness. Existing happy-path multi-line test passes the regression by virtue of `expectedWorkOrderCount` being set correctly, so the gap is "no targeted assertion that the race specifically can't recur." Capture for the next end-to-end run; would need a deliberately-induced race (e.g. duplicate WorkOrderCreated emission with a 100ms delay).

### 2.14 Kafka topic partitions — pre-declare with configurable counts

Today every event topic (`<service>.events` + matching `<topic>.dlt`) is auto-created on first publish (docker-compose `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`) with Kafka's default `num.partitions=1` — no `KAFKA_NUM_PARTITIONS` override, no `NewTopic` / `KafkaAdmin` bean, no `partitions:` setting in any `application-kafka.yml`. Means each consumer group has at most one active consumer per topic and the §2.6 cross-partition race regression is un-exercisable until partitions > 1.

Scope: flip `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` in docker-compose, add `NewTopic` beans in `shared/.../kafka` that read partition counts from `northwood.kafka.topics.<name>.partitions` (default 1 for the showcase, override to 3+ when exercising the partition-race test). Cover the 6 event topics + their DLT companions. Spring's `KafkaAdmin` creates the lot on service startup if missing. Keep RF=1 — single-broker constraint stays.

Audit items to clear before bumping any topic past 1 partition (full design in `docs/messaging-design.md` → *Hazards when scaling past 1 partition*):

1. **DLT partition count must match source.** The error-handler recoverer pins `record.partition()` (`KafkaMessagingAutoConfiguration.java:93`); mismatched counts cause poison-pill quarantine failure. Pre-declare both source + DLT.
2. **Verify saga concurrent-transition safety against a real broker.** Design is correct (`SELECT ... FOR UPDATE SKIP LOCKED` + optimistic `version` on `SagaPort`), but the synchronous test harness can't exercise the race. Need an integration-test target against a multi-partition broker.
3. **Audit projection write patterns for read-modify-write.** Atomic SQL increment is safe; load-process-write isn't. Candidates: `JdbcProductionPlanningProjection`, `JdbcProductCardProjection`, `JdbcStockBalanceWriter`.
4. **Re-test saga-prerequisite parking under realistic broker delay.** Multi-partition makes the "consequence event arrives before prerequisite saga row exists" path the common case, not the exception.
5. **Document topic-pre-declaration in the new-service checklist.** After auto-create flips off, new services must declare their topics or first publish throws `UnknownTopicOrPartitionException`.

### 2.15 Re-enable Liquibase once the schema stabilises

Liquibase was disabled across all 7 services on 2026-05-19 after a stale-volume boot failure (see `dev-done.md` for the consolidation slice). The 21 pre-existing changesets were folded into `db/northwood_erp.sql` and removed; `northwood.liquibase.enabled: false` is set in every service's `application.yml`; each master changelog is empty. On 2026-05-20 the baseline was split into schema (`db/northwood_erp.sql`) + seed (`db/northwood_erp_seed.sql`); together they are still the canonical source of truth for the showcase.

The disable is deliberate showcase-time hygiene — every slice that ships a structural change rebakes the baseline, the dev workflow is `docker compose down -v`, and the changeset-on-stale-volume failure mode (the one that triggered this) is a recurring loss.

Re-enable when:
- The baseline pair (`northwood_erp.sql` + `northwood_erp_seed.sql`) is no longer changing meaningfully (no more pending structural slices that would force a rebake).
- Production-style deploys are on the horizon (`docker compose down -v` stops being acceptable; preserving data across schema changes becomes load-bearing).
- A migration story for the existing demo dataset is in place (Liquibase changesets must work alongside whatever data-migration approach we adopt).

Re-enable steps:
1. Flip `northwood.liquibase.enabled: true` in every service's `application.yml` (sales, inventory, manufacturing, purchasing, product, finance, reporting).
2. Update the comment block in each `db/changelog/db.changelog-master.yaml` (and remove the "currently empty" framing).
3. Verify on a fresh-volume boot that the empty changelogs no-op cleanly against the baseline.
4. Future schema changes follow the original workflow: drop a `.sql` file in the service's `changes/` dir + add an `include` to its master.

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

### 3.5 SPA internationalisation

Adopt `react-i18next` (or equivalent ICU MessageFormat library) in both `demo-web-ui` and `erp-web-ui`. Extract every JSX text node, button label, placeholder, title, and aria-label to per-feature namespaced `en.json` bundles (~200+ strings per SPA). Add a top-bar locale switcher persisted to `localStorage`. Locale-aware number / date / currency rendering via `Intl.NumberFormat` + `Intl.DateTimeFormat` at every render site (today: `BigDecimal.toString()` + ISO dates).

Wire SPA-side error display off the backend's `ErrorResponse { code, params }` (depends on §1H landing first): each `code` maps to a bundle key under `errors.*`, params substituted by the i18n library's interpolation.

What stays English-keyed (not translated): `dbValue()` wire-format strings rendered as status badges — translate the *label* shown to the user, but the value comparison stays on the wire-format string. Same for currency codes (`AUD` / `NZD` / `USD` — ISO 4217, never localised).

Pull forward when a second locale is actually planned for the demo. Doing it speculatively turns into dead-weight maintenance until a real second-locale beat exists.

### 3.6 SQS + SNS as a Kafka alternative (AWS-native bus)

Swap Kafka for AWS-managed messaging when/if deploying to AWS, to drop MSK's cost + ops in favour of serverless pub/sub. The outbox/inbox design makes this an **adapter swap, not a rewrite**: the bus sits behind the `EventPublisher` interface (`KafkaEventPublisher` is the `@Profile("kafka")` impl) + the inbox dispatcher. The outbox drain loop, inbox dedup (`AbstractInboxHandler`), `EventEnvelope`, sagas, and projections all stay unchanged.

**Hard requirement — FIFO, not standard.** Per-aggregate ordering is load-bearing (Kafka partition key = `aggregateId`; sagas assume in-order per-aggregate delivery — see `docs/messaging-design.md`). Standard SNS/SQS gives *no* ordering and would corrupt saga state. Use **FIFO SNS → FIFO SQS** with **`MessageGroupId = aggregateId`** (the direct partition-key analog) and `MessageDeduplicationId = eventId` (the inbox table still dedups as a backstop).

Mapping: one FIFO SNS topic per producer service (was `<service>.events`) → one FIFO SQS queue per consumer (SNS fan-out); `<topic>.dlt` + hand-rolled bounded retry → **native SQS DLQ + redrive policy** (simpler than the current scheme); `traceparent` header → SNS/SQS message attributes.

Work: add an `SnsEventPublisher` (`EventPublisher` impl) + an `@SqsListener`-based dispatcher into the inbox handlers, both under a new `@Profile("sqs")` mirroring the kafka profile; add Spring Cloud AWS deps; provision topics / queues / subscriptions / filter-policies / DLQs (Terraform or auto-provisioning). Related: §2.14 — the partition pre-declaration work becomes moot under FIFO message groups.

Trade-off to accept: SQS deletes on consume, so there is **no replayable log** — replay means re-draining the outbox (the durable source of truth), and rebuilding a projection from scratch is less turnkey than Kafka offset-rewind. FIFO throughput ceilings (300/s, 3,000/s batched) are fine at showcase scale. **Do not** use EventBridge for this — it has no ordering guarantee. Pull forward only if an AWS deployment is actually on the table.

### 3.7 AWS deployment — per-service independent scaling + HA Postgres

Run each service + BFF on its own independently-scaling compute unit, with a single HA / fault-tolerant Postgres. (OAuth2/Keycloak and the bus are out of scope here — see §3.6 for the bus.) Fits the design well: the request/command path is stateless, and both background loops are **already multi-instance-safe** — the outbox drainer uses `FOR UPDATE SKIP LOCKED` (`JdbcOutboxAdapter.findPending`) and the saga workers use claim-and-lease + per-instance `workerId` (`SagaManager`). Horizontal scaling needs **no new coordination code**.

Compute (mostly infra, not code):
- Containerize each deployable — none exist yet (compose only runs infra; services run as local JVMs). `mvn spring-boot:build-image` (Buildpacks) → one image per service.
- One scaling unit per service + BFF (9 Spring Boot deployables): ASG + load balancer + target-tracking policy + health check (Actuator `/health` already exposed); a reusable Terraform module instantiated per service. **Raw per-service EC2 ASGs are the most laborious form — ECS Fargate (or ECS-on-EC2) gives the same independent per-service autoscaling with far less plumbing; prefer it unless EC2 is a hard requirement.**
- Externalize service URLs (BFF + saga aggregator call `localhost:808x` today) → per-service LB DNS / Cloud Map. Config, not code.
- The two SPAs are static → build → **S3 + CloudFront**, not EC2.

Postgres (easy, ~no code):
- **RDS Multi-AZ** or **Aurora PostgreSQL** — a single writer endpoint matches the schema-per-service-in-one-DB design (per-service roles + `search_path` unchanged). Code change ≈ datasource URL → cluster endpoint; failover handled by HikariCP + the JDBC driver.
- **RDS Proxy** once replicas scale up: N instances × Hikari pools can exhaust `max_connections` — Proxy multiplexes. This is the one place the two goals interact.

Caveat (parked with the bus): under multiple concurrent outbox drainers, strict per-aggregate *publish* ordering is the §2.14 "scaling past 1 partition" concern — not a blocker for request-path scaling. Pull forward only if an AWS deployment is actually on the table.

<!-- Section numbers below kept at their original §2.x values per the preamble's
     "stable historical anchors" rule — dev-done.md + design-notes.md have ~13
     cross-refs to §2.8 alone that would break under renumbering. -->

### 2.3 Soft-cancel WIP path

Deferred 2026-05-20 — demoted from §2 polish to §3 low-priority. Pull forward only if a soft-cancel narrative becomes part of the showcase.

**From:** §1.1 cancel-order slice 2026-05-06 (which shipped hard-cancel). Today a cancel arriving during `manufacturing_in_progress` immediately flips the WO to `cancelled` regardless of operation progress — WIP is written off. A more realistic ERP would let in-progress WOs finish then scrap the produced finished goods to a write-off bucket.

Scope:
- Decision: hard vs soft per WO based on a configurable threshold (e.g. operations-completed % > 50%)? Or a runtime flag on the cancel command?
- New events: `manufacturing.WorkOrderScrappedAfterCancel` (post-completion variant), inventory's WIP write-off.
- Finance hookup: when scrapped, post a write-off journal (Dr 5500 Write-off / Cr 1220 FG Inventory).

Wire into demo only after a clear narrative emerges — the showcase value of soft-cancel over hard-cancel is small and adds significant complexity.

### 2.4 CurrencyConverter depth

Deferred 2026-05-20 — demoted from §2 polish to §3 low-priority. Same lineage as §3.1 (multi-currency GL consolidation): the architecture is in place, single-currency end-to-end is the showcase. Pull forward only if the demo gains an FX-narrative beat.

Today the converter handles same-currency pass-through, inverse-rate fallback, and now `GET /api/exchange-rate?from=…&to=…&date=…` (shipped §3 Slice E 2026-05-06). Remaining depth:

- **Scheduled rate importer** — today rates are inserted manually for a few currency pairs. `@Scheduled` task fetching from an external feed would let the demo simulate a daily rate close.
- **Triangulation through a base currency** — out of scope today (schema doesn't model a base currency); listed in §4 below.

### 2.8 Pricing split — Slice E (cross-currency BoM rollup)

Deferred 2026-05-20 — demoted from §2 polish to §3 low-priority. Slices A–D shipped 2026-05-07 / 08 (split events, finance standard-cost projection, manufacturing-owned materials-cost rollup, BoM-walk + recursive parent recompute — see `dev-done.md`). Slice E was always conditional on the cross-currency throw firing in the demo dataset.

**Slice E** — wire `CurrencyConverter` into the BoM rollup so multi-currency component prices roll up to a target currency. Pull forward only if the cross-currency throw fires in the demo dataset.

### 2.12 Role meta-annotations for `warehouse_manager`, `auditor`, `sysadmin`

Deferred 2026-05-20 — demoted from §2 polish to §3 low-priority. Scaffolding-when-needed work; no current endpoint gates on these roles.

The 2026-05-13 `@PreAuthorize` → `@RequireXxx` sweep created annotations under `shared/api/security/` for the 10 realm roles that gate actual endpoints today. The other 3 realm roles defined in `db/keycloak/northwood-realm.json` — `warehouse_manager` (force-release reservations, post stock adjustments), `auditor` (read-only everywhere), `sysadmin` (Keycloak realm admin only) — don't have annotations because no endpoint gates on them today. Scaffold matching `@RequireWarehouseManager` / `@RequireAuditor` / `@RequireSysadmin` when the first endpoint needs them.

---

## 4. Out of scope (captured for completeness only)

These were considered and explicitly rejected; here so a future reader doesn't re-discover them and propose them as new work.

- **Inventory revaluation, average-cost rolling, FIFO/LIFO** — out of scope for the showcase. The `unitCost` from each receipt is taken at face value and never reconciled.
- **Reversal-of-reversal** — schema rejects (reversed entries are immutable). If business needs to "undo a reversal" it would post a new entry that re-applies the original's effect, not modify the existing chain.
- **Triangulation in CurrencyConverter** — schema doesn't model a base currency explicitly; out of scope until that decision is made.
- **`product_planning` / `product_pricing` sub-tables on product master** — locked 2026-05-04: stay with single columns going forward. Revisit only if a future facet has a multi-row shape (e.g. an approved-vendor list, which is genuinely a child collection).
