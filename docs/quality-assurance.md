# Quality assurance

How Northwood is tested: the test tiers, what each one is allowed to cover, the
structural-coverage contract that is the *real* guarantee, the JaCoCo line/branch
numbers, requirement traceability, and the build-time quality gates.

This file is the QA **overview + index**. It does not restate the detailed test
*recipes* — those live where the code does:

- **`docs/architecture.md`** → *Every aggregate gets a domain unit test* and *`Jdbc*`
  persistence gets an integration test (`*IT`)* — the per-shape recipes, Testcontainers
  setup, and the FK-seed / outbox-delta / `reconstitute(...)` gotchas.
- **`docs/test-harness-dsl.md`** → the in-memory end-to-end acceptance DSL (the
  `*DslTest` tier): why it exists, what it must **not** absorb, and how the World /
  port-double / synchronous-bus machinery works.
- **`docs/requirement-coverage.md`** → the REQ-by-REQ → test mapping matrix.
- **`docs/messaging.md`** → the reliability / idempotency test matrix for the
  outbox→Kafka→inbox path.

---

## 1. Testing philosophy

Two ideas drive every testing decision here.

**Structural coverage is the contract; line coverage is a thermometer.** The load-bearing
guarantee is not a percentage — it is a set of *structural* rules enforced at code review
(§5): every aggregate with a guard has a sibling domain unit test; every `Jdbc*` persistence
class has a Testcontainers `*IT`; every cross-service business outcome has an acceptance
`*DslTest`. JaCoCo (§4) measures how thoroughly those tests exercise the code, but the
existence-and-shape rules are what stop coverage rotting. A green 75% with a missing
aggregate test is a worse state than a red 60% where every aggregate is pinned.

**The test pyramid maps onto the hexagonal layers.** Fast, Docker-free unit tests at the
bottom (domain + application, plain JUnit + AssertJ + Mockito); Testcontainers integration
tests in the middle (the infrastructure seam — real Postgres, real SQL, real triggers);
in-memory end-to-end acceptance tests across the top (the whole saga + handler + serde stack,
no Postgres/Kafka/Spring). `mvn test` runs only the bottom; `mvn verify` adds the rest.

---

## 2. Test tiers

| Tier | Lives in | Runner / phase | Exercises | Count |
|---|---|---|---|---|
| **Domain unit** | `*-service/src/test/.../domain/*Test.java` | Surefire / `test` | Single-aggregate invariants — factories, null/blank/status guards, no-op suppression, fold ladders, BOM cycles, journal balance. Plain JUnit + AssertJ, no Spring. | 23 classes |
| **Application unit** | `*-service/src/test/.../application/*Test.java` (incl. `inbox/*HandlerTest`) | Surefire / `test` | Application-service orchestration + inbox-handler logic, with Mockito doubles for ports. | 64 classes |
| **Persistence / seam IT** | `*-service/src/test/.../*IT.java` | **Failsafe / `verify`** | The infrastructure seam against a real Postgres 17 (Testcontainers): `Jdbc*RepositoryIT` round-trips + DB triggers, `*SeamIT` cross-service projection edges, saga-adapter lease/claim ITs. | 49 classes |
| **Acceptance DSL (E2E)** | `test-harness/.../**/*DslTest.java` | Surefire / `test` | Cross-service business **outcomes** end-to-end over the real saga + handlers + Jackson serde on an in-memory synchronous bus — no Postgres, Kafka, or Spring context. | 22 classes |
| **Messaging / shared** | `shared/src/test/.../*Test.java` | Surefire / `test` | Outbox drainer, inbox dedup, saga base, DLT classification, Keycloak role converter. | — |

Counts are class counts as of 2026-06-14. Across all tiers: **1,186 `@Test`** + 3
`@ParameterizedTest` methods in ~180 test classes. The acceptance DSL tier is powered by
**54 in-memory port doubles** (`test-harness/.../inmemory/InMemory*`) plus a `SynchronousBus`
— changing a `*Port` / `*Lookup` / `*Writer` interface means updating its double, so a full
`mvn clean install` (not a single-module `-pl x test`) is required to catch the break.

### What each tier must *not* absorb

The tiers are not redundant — each has a job the others are forbidden from doing (full
rationale in `docs/test-harness-dsl.md` §8):

- **Domain unit** owns invariants. It must not reach for a DB or a mock saga — if a test
  needs Postgres, it is the wrong tier.
- **Persistence IT** owns the SQL ↔ object seam. It must not re-test domain invariants
  already covered by the domain unit test, and it never mocks `JdbcTemplate` — a mocked-JDBC
  "unit test" of a `Jdbc*` class is explicitly banned (it tests the mock, not the SQL).
- **Acceptance DSL** owns cross-service outcomes. It must not assert low-level row shapes
  (that is the IT's job) — it asserts business results (order shipped, invoice raised,
  GL balanced).

---

## 3. Running the tests

```powershell
mvn test                                  # all unit tiers, Docker-free, fast
mvn verify                                # unit + Testcontainers ITs (needs Docker running)
mvn clean install                         # full reactor: build + verify across all modules

mvn -pl finance-service test              # one module, unit only
mvn -pl finance-service verify            # one module, incl. its ITs
mvn -pl purchasing-service test -Dtest=JdbcPurchaseOrderPaymentProjectionIT
```

> **Gotcha — `-Dtest=ClassName` with `@Nested`.** A bare class-name selector runs only the
> nested tests and silently skips top-level `@Test`s (Surefire exits 0, misleading). Verify a
> top-level test with `#method` or the default no-selector run.

> **Gotcha — unit vs IT split is real.** `mvn test` does **not** run `*IT` classes (Failsafe
> binds to `verify`). Conversely a Mockito unit-test regression will **not** be caught by a
> run that only exercises Testcontainers ITs. Before committing any change to a status enum,
> schema CHECK, or shared interface, run the **full** `mvn clean install` — a partial run is
> how a sweep once shipped a CHECK/enum mismatch that every unit-and-IT run would have caught
> (see §6).

---

## 4. Code coverage (JaCoCo)

Coverage is instrumented by the **`jacoco-maven-plugin`** (pinned `0.8.13` in the parent POM,
inherited by every module). The agent is woven into **both** test JVMs — `prepare-agent`
for the Surefire (unit) run and `prepare-agent-integration` for the Failsafe (IT) run — so
each writes its own `target/jacoco.exec` / `target/jacoco-it.exec`. A `merge` + `report`
pair (both in the `verify` phase) folds the two into one per-module report.

### Report locations (after `mvn verify`)

| Report | Path |
|---|---|
| **Project-wide aggregate** (unit + IT, all 7 services + `shared` + `shared-kernel`) | `test-harness/target/site/jacoco-aggregate/index.html` |
| Per-module (unit + IT merged) | `<module>/target/site/jacoco/index.html` |
| Raw execution data | `<module>/target/jacoco.exec`, `jacoco-it.exec`, `jacoco-merged.exec` |

`test-harness` is the aggregator because it depends on every service module; its
`report-aggregate` execution scans the reactor for both `.exec` flavours and produces the
single cross-module number. It runs last in the reactor, after every module's tests have
written their data.

### Coverage snapshot — 2026-06-14

Regenerate any time with `mvn clean verify`. Headline (aggregate, unit + IT combined):

| Metric | Covered / Total | % |
|---|---|---|
| **Instructions** | 46,894 / 63,013 | **74.4%** |
| **Lines** | 9,029 / 12,141 | **74.4%** |
| **Branches** | 2,017 / 3,099 | **65.1%** |
| **Methods** | 1,925 / 2,807 | **68.6%** |
| **Classes** | 492 / 700 | **70.3%** |

Per module (line / branch):

| Module | Line | Branch |
|---|---|---|
| product-service | 84.4% | 85.9% |
| inventory-service | 78.0% | 68.5% |
| finance-service | 70.6% | 60.7% |
| sales-service | 66.5% | 63.4% |
| manufacturing-service | 65.1% | 62.5% |
| shared-kernel | 55.7% | 90.7% |
| shared | 58.0% | 43.5% |
| purchasing-service | 57.4% | 49.6% |
| reporting-service | 32.5% | 31.9% |

Reading the low end: **`reporting-service`** is projection-and-query-port heavy — much of it
is CQRS read-side SQL exercised only through `*QueryPortIT` / `*ProjectionIT`, and several
dashboards are reachable only via the live demo, not an automated test. **`shared`** carries
Kafka/auto-config wiring whose branches (profile gates, error-handler classification) only
light up under the delivery ITs, which are a thin slice today. These are the honest gaps,
not measurement artifacts.

### No coverage gate — on purpose

There is **no `check` goal / no minimum-coverage threshold** that fails the build. Coverage
here is a reporting signal, not a merge gate: a hard percentage gate incentivises
low-value tests that move the number without pinning behaviour, and it would fight the
structural contract (§5) that is the actual guarantee. If a CI gate is wanted later, add a
`jacoco:check` execution with per-bundle rules — but prefer gating on the *structural* rules.

**Excluded from the aggregate:** `erp-web-ui-bff` (a thin BFF proxy — see
`docs/infrastructure.md`) is not a `test-harness` dependency and so is outside the aggregate;
the `*-events` jars appear in the aggregate but carry no tests of their own (they are
exercised transitively through DSL-tier serde).

---

## 5. The structural-coverage contract

This is the guarantee JaCoCo *measures* but does not *enforce*. It is enforced at code
review, and each rule is grep-checkable:

- **Every aggregate with a guard has a domain unit test.** A new mutator on an aggregate
  ships in the same PR as its null/blank/status/no-op/event tests — the test file is part of
  the aggregate's interface, not a follow-up. (Pure read-model holders with no guards are
  exempt.) → `docs/architecture.md` → *Every aggregate gets a domain unit test*.
- **Every `Jdbc*` persistence class has a Testcontainers `*IT`** against real Postgres —
  never a mocked-`JdbcTemplate` unit test. → `docs/architecture.md` → *`Jdbc*` persistence
  gets an integration test*.
- **Every cross-service business outcome has an acceptance `*DslTest`** at the harness tier.
- **Validation is tested at every layer it is asserted** (api fail-fast → application →
  domain pure-read `assertXxx()`), never relying solely on a DB CHECK. → `docs/validations.md`.

Why structural-over-numeric: a concept that "deserves a test" is one with identity and
behaviour (an aggregate, a SQL seam, a saga outcome). Tying the existence of a test to the
*shape* of the code, rather than to a coverage percentage, is the same deltas-get-aggregates
discipline applied to the test suite — see `docs/conventions.md`.

---

## 6. Requirement coverage

`docs/requirement-coverage.md` maps every requirement in `docs/business-requirements.md` to
the test(s) that exercise it, so traceability drift is visible at a glance. It is kept in
sync as requirements or tests change.

Scope as of 2026-06-14 — **9 requirement areas** (`REQ-PROD`, `REQ-SAL`, `REQ-INV`,
`REQ-MFG`, `REQ-PUR`, `REQ-FIN`, `REQ-RPT`, `REQ-SEC`, `REQ-XBC` cross-bounded-context):

- **66** requirement rows ✅ covered
- **2** ⚠️ partial / deferred (notably hard-cancel-during-manufacturing, deferred with the
  WO↔sales-line binding removed)
- **4** ❌ explicit gaps (named, not silent)

Requirements are referenced by their `REQ-XXX-NNN` id, never by a backlog coordinate — code,
tests, and docs stay self-contained (`CLAUDE.md` → *Never reference a backlog/todo item
number outside the backlog*).

---

## 7. Build-time quality gates

Beyond the tests themselves, a change is expected to clear:

- **`mvn clean install`** — full reactor compile + unit + IT across all modules. The
  authoritative pre-commit gate. Requires Docker for the Testcontainers ITs.
- **Hexagonal layering greps** — the machine-checkable import rules in `CLAUDE.md` (api↛domain,
  application↛infrastructure, no `JdbcTemplate` in `application/`, etc.). Any new match is a
  review fail.
- **Backlog-tag sweep** — `Grep '§|dev-todo|dev-done|Slice [A-G0-9]'` over any edited tracked
  file → expect 0 hits.
- **erp-web-ui type gate** — for any web-ui edit, gate on **`npm run build`** (the root
  tsconfig uses project references; a bare `tsc --noEmit` is a near no-op).

---

## 8. Known gaps & non-goals

Stated plainly so they are not mistaken for coverage:

- **No mutation testing** (e.g. PIT). Coverage measures *execution*, not *assertion strength*.
- **No consumer-driven contract tests** (e.g. Pact) between services. The events-jar
  compile-dependency + the DSL-tier serde tests are the substitute: a producer schema change
  breaks consumers at compile time.
- **Thin delivery-IT slice.** Real-Kafka ordering / retry-backoff / DLT-redrive ITs exist but
  are not yet broad; much of the `shared` messaging branch-coverage gap lives here.
- **`reporting-service` dashboards** are partly verified only through the live demo
  walkthrough (`docs/demo-script.md`), not automated tests — the §4 low number reflects this.
- **No load / performance / chaos testing.** The architecture is built for it (per-aggregate
  partition keys, `SKIP LOCKED` drains) but it is not exercised under load in CI. The
  correctness-under-contention load test is **designed but not yet built** —
  `docs/concurrent-load-test.md` (shared Testcontainers backend, Gatling REST + Playwright UI
  executions, invariant-based assertions, and a demo mode over the live compose + LGTM stack).
