# Reactive, virtual threads, and effect systems — an options review

> **Origin & scope.** This began as "should Northwood adopt the Spring reactive
> stack?" The answer for our workload is **no** (Part B). It has since grown into
> a comparative review of the whole concurrency-and-persistence option space,
> evaluated for the workload where these choices actually pay off —
> **streaming-heavy / extreme-concurrency** services. **Part A** is that general
> review; **Part B** applies it to Northwood's transactional,
> connection-pool-bound reality and records the migration blast radius.

---

# Part A — The options, reviewed for streaming-heavy / extreme-concurrency

## A1. The mental model: two orthogonal axes

The mistake almost everyone makes is treating *reactive*, *virtual threads*, and
*effect systems* as points on one line. They sit on **two independent axes**:

| | Governs | Options |
|---|---|---|
| **Axis 1 — composition model** | *how* you express & compose logic, errors, concurrency | imperative · reactive-streams (Reactor) · functional-effect (ZIO / Cats Effect) |
| **Axis 2 — I/O substrate** | *scalability* — how a wait is paid for | blocking on a platform thread · blocking-but-cheap (Loom virtual threads) · non-blocking (R2DBC / native async) |

**Scalability comes entirely from Axis 2; composition style is Axis 1.** Reactor
*bundles* a particular Axis-1 (streams) with a particular Axis-2 (non-blocking,
given a non-blocking driver), which is exactly why the two get conflated — but
they are separable. The concrete stacks are the cells of the matrix:

| Axis 1 ↓ / Axis 2 → | Blocking (platform threads) | Loom (virtual threads) | Non-blocking (R2DBC/native) |
|---|---|---|---|
| **Imperative** | Spring MVC + JDBC *(Northwood today)* | Spring MVC + JDBC + `virtual-threads` | — (imperative can't consume async I/O without blocking → pointless) |
| **Reactive-streams** | WebFlux + JDBC *(worst-of-both-worlds)* | redundant (Reactor doesn't need Loom) | **WebFlux + R2DBC** *(the "true reactive" stack)* |
| **Functional-effect** | ZIO/CE + JDBC *(isolated-blocking)* | ZIO/CE, blocking pool on virtual threads | **ZIO/CE + Skunk / R2DBC / jasync** *(coherent functional-reactive)* |

Two cells are the genuinely strong options for streaming/extreme-concurrency:
**WebFlux + R2DBC** and **functional-effect + a non-blocking driver**. Everything
else is either a baseline, an anti-pattern, or redundant.

## A2. Axis 2 (substrate) — what actually determines scalability

**Blocking on a platform thread.** JDBC. `executeQuery()` is synchronous over a
blocking `java.net.Socket`; the calling thread is held until the DB responds. One
platform thread (~1 MB stack) per in-flight query.

**Loom / virtual threads (JDK 21).** A virtual thread blocked on a JDBC socket
read **unmounts its carrier**, so thousands of blocked virtual threads ride a few
carriers — synchronous code, full ecosystem, no virality. *Caveat:* drivers can
**pin** the carrier inside `synchronized` blocks (PG-JDBC migrated to
`ReentrantLock` to fix this); when pinned, the virtual thread doesn't unmount.

**Non-blocking (R2DBC / native async).** The reference R2DBC drivers
(`r2dbc-postgresql` et al.) are **Netty-based** and speak the DB wire protocol
over **non-blocking NIO sockets**; a query registers a callback and rows arrive as
a `Publisher<Row>` on an event loop — **no thread parked on the network wait.**
This is genuine, not a facade. (Nuance: *non-blocking ≠ threadless* — event-loop
threads still do protocol/decoding work; what's removed is the coupling between
thread count and concurrent-query count.)

**Different drivers from JDBC — not the same driver.** R2DBC and JDBC are
separate, parallel driver ecosystems on separate SPIs (`io.r2dbc.spi.*` vs
`java.sql.*`); a JDBC driver cannot serve R2DBC or vice versa. They can't be
unified because the JDBC SPI is *blocking by specification* and the R2DBC SPI is
*non-blocking by specification* — so an R2DBC driver re-implements the database
wire protocol from scratch on Netty rather than wrapping the JDBC one. For
PostgreSQL:

| | JDBC | R2DBC |
|---|---|---|
| artifact | `org.postgresql:postgresql` (PgJDBC) | `org.postgresql:r2dbc-postgresql` (separate codebase, Netty-based) |
| URL | `jdbc:postgresql://…` | `r2dbc:postgresql://…` |

What they share is only the **database and its wire protocol** — the server can't
tell the two clients apart. Consequences: the R2DBC driver ecosystem is
**narrower** (Postgres/MySQL/MariaDB/MSSQL/Oracle/H2 have drivers; many databases
have none or immature ones), and you typically **keep the JDBC driver too**
because Liquibase/Flyway are JDBC-only — the "two connection stacks per service"
item (§B2, row 3). (A community "R2DBC-over-JDBC" shim exists but runs blocking
JDBC on a thread pool — *not* genuinely non-blocking, so it defeats the point.)
The Loom carrier-pinning caveat above is a **PgJDBC** concern only;
`r2dbc-postgresql` never blocks, so pinning is N/A for it.

**The async / semantically-blocking / blocking triple.** Effect systems and
reactive runtimes distinguish three modes, and segregating a non-blocking compute
pool from a dedicated blocking pool is *the* canonical technique:

| Runtime | Compute / non-blocking | Blocking pool | Shift primitive |
|---|---|---|---|
| ZIO | default executor | `Blocking` executor | `ZIO.blocking` |
| Cats Effect 3 | work-stealing pool | blocking pool | `IO.blocking` |
| Reactor | `Schedulers.parallel()` | `Schedulers.boundedElastic()` | `subscribeOn`/`publishOn` |
| Akka | default dispatcher | dedicated blocking dispatcher | `Future` on it |
| Node.js | event loop | libuv thread pool | (automatic) |
| Go | netpoller | auto OS threads for syscalls | (runtime) |

The decisive point: **this technique provides *isolation* (bulkheading), not
*conversion*.** Routing JDBC to a blocking pool stops it starving async work, but
the pool is still **one OS thread per in-flight query**. You can park a million
*fibers/virtual threads* on the DB cheaply; only `pool-size` queries actually
execute.

**⚠ The connection-pool ceiling — the great equalizer.** Every query, on any
substrate, needs a DB connection, and databases allow a bounded number (Postgres
`max_connections` ≈ 100; a per-service pool of 10–50). So **only `pool-size`
queries execute at once regardless of substrate.** Non-blocking I/O and virtual
threads make the *waiters* cheap; neither raises the actual concurrent-DB-*work*
ceiling. The payoff of non-blocking/Loom is a cheaper waiting room (and, for
non-blocking, streaming with backpressure) — **not** more throughput against a
saturated pool. This single fact bounds how much any of these options can help a
DB-bound app.

**So: is R2DBC's advantage over JDBC real?** Yes, but **narrow and shrinking**:
(1) the connection-pool ceiling caps both identically; (2) Loom gives blocking
JDBC the cheap-waiter property on JDK 21; (3) ecosystem cost (no Liquibase/Flyway,
weaker Spring Data R2DBC, context-bound reactive transactions). R2DBC genuinely
wins for **streaming large result sets with backpressure** and **extreme
concurrent-connection counts with I/O-wait-dominated queries** — and little else.

**End-to-end non-blocking is a whole-service property, not a request-path one.**
Non-blocking is a weakest-link property — one blocking call on an event-loop
thread stalls everything sharing it — so "the service is non-blocking" requires
*every* I/O touchpoint to be non-blocking or offloaded, not just `api` +
`application` + the DB driver. The request path (reactive HTTP runtime → controller
→ service → R2DBC, **zero `.block()`**) is necessary but not sufficient; a service
also does **messaging** (Kafka listeners are blocking — true non-blocking needs
`reactor-kafka`), outbound HTTP, and auth/JWKS, and carries *incidental* blockers
that silently park event-loop threads — **synchronous logging appenders,
metrics/trace exporters, DNS lookups**. The workable definition is "**nothing
parks an event-loop thread**": make what you can native non-blocking, and
**offload the rest to a bounded blocking pool** (`boundedElastic`) — e.g.
Liquibase, which is JDBC-only and runs at startup. Two limits remain: it still
doesn't beat the **connection-pool ceiling** (non-blocking ≠ unbounded), and it
doesn't help **CPU-bound work**, which must also be offloaded or it stalls the
loop. For Northwood this means `api` + `application` + R2DBC is
necessary-but-not-sufficient — the **outbox / inbox / saga + Kafka core** is the
larger remaining surface, i.e. the full §B2 lift.

## A3. Axis 1 (composition) — models and their costs

**Imperative.** Plain method calls; `T findById(...)`. Cheapest cognitively;
stack traces intact; pairs perfectly with Loom. No streaming/backpressure model of
its own.

**Reactive-streams (Reactor).** `Mono`/`Flux`. Built-in backpressure, rich
operators. But **viral** ("function coloring"): a method returning `Mono`/`Flux`
forces every caller to also return reactive or `.block()`, so reactivity spreads
from each I/O leaf up to the entry point. And it conflates concerns — empty `Mono`
= "not found" folds a *domain* effect (absence) into a *substrate* type
(asynchrony). See Part B for what this does to a layered codebase.

**Functional-effect (ZIO / Cats Effect).** `ZIO[R,E,A]` / `IO[A]`: typed error
channel, structured concurrency, resource safety (`Scope`/`Resource`), referential
transparency, excellent testability, and streaming via `ZStream` / `fs2.Stream`
with backpressure. **Also viral** — arguably *more* so (everything is an effect
value) — but the color is more principled and the ergonomics (for-comprehensions)
are nicer than Reactor operators. Key limit: an effect system is an **Axis-1**
choice; its Axis-2 result is **driver-gated** (next section). The only option that
*removes* virality entirely is Loom.

## A4. The library landscape — functional + non-blocking relational access

For "an R2DBC-equivalent in the functional-effect world," the libraries split
sharply by whether they're **truly non-blocking** or **functional-over-blocking
JDBC**:

| Library | Effect basis | Driver / I/O | Non-blocking? | Use from ZIO via |
|---|---|---|---|---|
| **Skunk** | Cats Effect + fs2 | native Postgres protocol over NIO (no JDBC) | **Yes** — gold standard | `zio-interop-cats` |
| **Quill** `quill-jasync-*` | Future / ZIO module | jasync-sql (Netty async PG/MySQL) | **Yes** | ZIO module |
| **R2DBC directly** | raw SPI | R2DBC driver (Netty) | **Yes** | `zio-interop-reactivestreams` (`Publisher`↔`ZStream`) |
| zio-jdbc (official) | ZIO | JDBC | No — blocking pool | native |
| Doobie | Cats Effect + fs2 | JDBC | No — blocking pool | `zio-interop-cats` |
| Quill `quill-jdbc-zio` | ZIO | JDBC | No — blocking pool | native |
| zio-sql (official) | ZIO | JDBC | No — blocking pool | native (experimental) |
| Tranzactio | ZIO | wraps Doobie/Anorm (JDBC) | No — blocking pool | native |

The three real **R2DBC-equivalents**:

1. **Skunk** — the strongest, arguably *better* than R2DBC: purely functional
   Postgres (tpolecat), native wire protocol on **fs2 + Cats Effect** over
   non-blocking sockets, native streaming/backpressure (`fs2.Stream`),
   `LISTEN/NOTIFY`, `COPY`. **Postgres-only.** Cats Effect, run from ZIO via
   `zio-interop-cats`.
2. **Quill + jasync** — non-blocking via **jasync-sql** (maintained Netty driver,
   successor to the abandoned `postgresql-async`) plus a compile-time-checked
   query DSL; ZIO-native-ish. (Quill's `*-jdbc-zio` contexts are blocking.)
3. **R2DBC itself, from ZIO** — bridge the driver's `Publisher<T>` via
   `zio-interop-reactivestreams`. Lowest-level, but literally "R2DBC in ZIO."

**Center-of-gravity caveat.** The non-blocking *functional* DB story in Scala
lives in **Cats Effect** (Skunk native, Doobie for JDBC), **not ZIO-native**:
ZIO's first-party DB libs (`zio-jdbc`, `zio-sql`, Tranzactio) are all
JDBC/blocking — the Axis-1-functional, Axis-2-blocking quadrant. For true
non-blocking you lean on Skunk-via-interop, jasync, or R2DBC-via-interop.

## A5. Per-option scorecard (streaming-heavy / extreme-concurrency)

| Stack | Axis 1 | Axis 2 | Streaming + backpressure | JVM maturity | Wins when |
|---|---|---|---|---|---|
| Spring MVC + JDBC | imperative | blocking | no | highest | baseline; low/moderate concurrency |
| **Spring MVC + JDBC + Loom** | imperative | cheap-blocking | no (manual) | high (JDK 21) | high concurrency, DB-bound, want simplicity; **default modern choice** |
| **Spring WebFlux + R2DBC** | reactive | non-blocking | **yes** (`Flux`) | high | streaming, very high connection counts, already a Spring shop |
| ZIO/CE + JDBC | functional | isolated-blocking | partial | high (Scala) | want typed-effects, concurrency DB-bound anyway |
| **ZIO/CE + Skunk / R2DBC / jasync** | functional | non-blocking | **yes** (`ZStream`/`fs2`) | medium (Scala) | streaming + typed-effects + Scala shop; theoretically optimal |

For the target workload, the **live contenders are WebFlux + R2DBC** and
**functional-effect + Skunk/R2DBC**. The functional-effect option is the most
capable (typed errors, resource safety, structured concurrency on top of genuine
non-blocking I/O) and the least accessible (Scala, smaller ecosystem, a from-
scratch architecture). WebFlux + R2DBC is the pragmatic JVM-mainstream choice when
streaming/backpressure is the actual requirement.

## A6. Decision guidance

- **DB-bound, moderate concurrency, JVM mainstream** → Spring MVC + JDBC, add
  **Loom** if connection-waiters get expensive. Don't reach for reactive.
- **Streaming large result sets / SSE / WebSocket fan-out with backpressure** →
  this is where non-blocking earns its keep: **WebFlux + R2DBC** (Spring shop) or
  **Skunk/fs2** / **ZStream** (functional shop).
- **Extreme concurrent-connection counts, I/O-wait-dominated, thread overhead
  measured as the bottleneck** → non-blocking (R2DBC/Skunk). But first confirm the
  **connection pool** isn't the real ceiling — usually it is, and then Loom
  suffices.
- **You want typed errors / structured concurrency / referential transparency as
  first-class** → functional-effect (ZIO/CE). That's an Axis-1 want; pair it with a
  non-blocking driver (Skunk) only if you also need Axis-2.

---

# Part B — Applied to Northwood (transactional, connection-pool-bound)

Northwood is an internal ERP: overwhelmingly transactional CRUD + orchestration,
no high-fan-out streaming, no extreme idle-connection counts, throughput **bounded
by the connection pool**. Per Part A, none of the non-blocking options can pull
meaningfully ahead of cheap blocking. Practicality ranking for *this* workload:

> **Loom + JDBC ≫ stay-as-is ≫ Reactor + R2DBC ≫ ZIO/CE + Skunk/R2DBC**

Loom + JDBC + plain Spring delivers ~equivalent *effective* scalability with zero
migration: synchronous code, no virality, full ecosystem, and `*Repository`
staying validly in `domain/` (§B3). The reactive options are net-negative here;
the functional-effect options are a language-and-framework rewrite. The rest of
Part B records *why* a reactive migration is invasive, in case the question
returns.

## B0. The most fundamental reason: Northwood is user-input-driven

Before the migration mechanics, the deepest reason the reactive case collapses
here: end-to-end, Northwood is **user-input-driven**, and that is categorically
not a streaming workload.

- Every interaction is a **discrete transaction** triggered by a human action
  (place order, post shipment, record payment) — a bounded request → bounded unit
  of work → bounded response, not a continuous data flow.
- So load is **human-paced and bounded**: tens-to-hundreds of operators clicking,
  never an autonomous firehose. A person cannot outpace the server.
- Therefore there is **no producer-outpaces-consumer condition — and backpressure,
  reactive streaming's signature feature, has nothing to act on.** That, more than
  "connection-pool-bound," is the root reason the non-blocking stack buys nothing.
- **Event-driven ≠ streaming.** The outbox/inbox/Kafka bus is choreography between
  services, not a data stream; every event is a *consequence* of a user action, so
  the whole topology — bus included — is human-paced. Trace any event back and you
  hit a click.

The only thing that would flip this is a *non-interactive* feature where a
producer genuinely outpaces a consumer — a bulk CSV export of millions of rows, an
analytics firehose, CDC ingestion. Northwood has none; its lone streaming surface
is the low-scale BFF SSE drawers (§B5), which don't move the verdict.

## B1. Why it isn't a drop-in, and why it cascades

The stack is **blocking top to bottom**: `spring-boot-starter-web` (servlet MVC)
over `spring-boot-starter-data-jdbc` in all eight services. WebFlux only pays off
when the *entire* path is non-blocking; over blocking JDBC it's the worst of both
worlds (every DB call wrapped in `Mono.fromCallable(...).subscribeOn(boundedElastic())`
— a thread pool with reactive ceremony). Going *genuinely* reactive means
replacing the persistence foundation (**JDBC → R2DBC**), and that cascades because
reactive types are **viral**: `Mono`/`Flux` propagate from each I/O leaf up
through every transitive caller. Code **on** the I/O path is rewritten; code
**off** it (pure domain logic, DTOs, rules) is reused unchanged — but in an ERP
most application methods are on the path.

## B2. Blast radius — what a reactive migration touches

| # | Subsystem | Today | Under reactive | Severity |
|---|---|---|---|---|
| 1 | **Persistence** (`infrastructure/persistence/`) | `JdbcTemplate` + `RowMapper` in every `Jdbc*` | Rewrite against `R2dbcEntityTemplate`/`DatabaseClient`; all return `Mono`/`Flux`. Spring Data JDBC ≠ Data R2DBC | **Severe** |
| 2 | **`search_path` per connection** | Hikari `connection-init-sql` sets `search_path = <svc>, shared` | `r2dbc-pool` has no `connection-init-sql`; reimplement `SET search_path` via a `ConnectionFactory` decorator. Load-bearing invariant | **Severe** |
| 3 | **Liquibase** | `SpringLiquibase` over a JDBC `DataSource` | Liquibase is JDBC-only — keep a JDBC `DataSource` for migrations alongside R2DBC. Two connection stacks | High |
| 4 | **Transactions** | 97 `@Transactional` across 26 files | Needs `R2dbcTransactionManager`, only on `Mono`/`Flux` methods, tx bound to Reactor context | High |
| 5 | **Outbox / inbox / Saga** | `JdbcTemplate`-based; outbox INSERT shares the aggregate tx | All reactive R2DBC; reactive Kafka means `reactor-kafka` — different model | High |
| 6 | **Hexagonal contract** (`application/` + `domain/`) | ports/aggregates return plain types | Aggregates stay sync, but 15 `*Repository` ports (in `domain/`) gain Reactor types; nearly every `application/` method rewritten. §B3, §B4 | High |
| 7 | **Web layer** (`api/`) | servlet; `ResponseEntity`, `OncePerRequestFilter` | `ResponseEntity` survives; any servlet `OncePerRequestFilter` → `WebFilter` | Medium |
| 8 | **Security** | servlet `SecurityFilterChain` | → reactive `SecurityWebFilterChain` | Medium |
| 9 | **Tests** | `MockMvc`; `Jdbc*IT` Testcontainers | `WebTestClient`; R2DBC-driver ITs + `StepVerifier`; large rewrite | Medium |
| 10 | **springdoc** | `...-webmvc-ui` | `...-webflux-ui` | Low |
| 11 | **BFFs** | servlet + JDK `HttpClient` | least coupled; §B5 | Low |

## B3. Where the repository interface would have to move (domain vs application)

We put `*Repository` in `domain/` because "Repository" is a DDD term (Evans), and
the codebase enforces "every `*Repository` in `domain/` has a sibling aggregate
root." That placement is **correct — conditional on a synchronous substrate.** A
reactive return type puts Reactor on the domain classpath, breaking "domain
imports `shared-kernel` only."

**Deciding principle:** a port's home is the layer that can legitimately *name
every type in its signature*. Sync `Optional<SalesOrder>` names only domain + JDK
→ `domain/` valid. Reactive `Mono<SalesOrder>` names a substrate type → not valid.
(Matches Dependency Inversion: the port's real client is `SalesOrderService`, an
application class — no aggregate calls its own repository.) **Tell:** every *other*
I/O port (`*QueryPort`, `*Lookup`, `*Port`) already lives in `application/`;
`*Repository` is the lone exception placed in `domain/`.

- **Option A — move `*Repository` to `application/`** (Clean Architecture / strict
  ports-and-adapters). Fits because `application/` already imports Spring; `domain/`
  stays pure. Cost: the DDD-reserved suffix now lives in application (relax the rule
  or rename to `*Port`).
- **Option B — keep it in `domain/`, depend on the *abstraction* not Reactor:**

  ```java
  // pure JDK — no third-party dep; and it does NOT conflate absence with asynchrony
  CompletionStage<Optional<SalesOrder>> findById(SalesOrderId id);
  CompletionStage<Void> save(SalesOrder order);
  // multi-value reads: java.util.concurrent.Flow.Publisher<T>
  ```

  Mirrors "depend on `DataSource`, not Hikari." The principled case (**effects-in-
  types**): I/O is *essential*, so declaring it via an abstract effect holder is
  honest — and the *accidental* part was only ever *Reactor specifically*. Same
  discipline the codebase already applies with **`Optional`** (declares the absence
  effect even when some impls always find the row). Well-formedness criterion: a
  holder is honest only if it **admits its own synchronous instance** (`Mono.just`,
  `completedFuture` both do) — "the effect may be none in the implementation."
  Java caveat: lacking a pure `IO`/HKT, Java holders (`CompletionStage`,
  `Flow.Publisher`) bake in *asynchrony* specifically, so they declare "may be
  async," slightly stronger than "is I/O."

**Decision rule:** synchronous substrate (incl. **virtual threads**) → keep in
`domain/`; truly reactive → `application/` (A) or domain-with-`CompletionStage`
(B). That virtual threads keep it validly in `domain/` is an architectural
argument in their favour, not just a performance one.

## B4. Exact changes in application & domain

Domain **aggregates stay 100% synchronous** (`SalesOrder.place(...)`,
`order.cancel(...)`, `Assert.state(...)`, `pullPendingEvents()`) — no I/O, no
color. The intrusion is the 15 `*Repository` ports (§B3). The **application layer**
is where nearly every method is rewritten:

```java
// today
@Transactional
public void cancel(CancelOrderCommand command) {
    SalesOrder order = salesOrders.findById(SalesOrderId.of(command.salesOrderHeaderId()))
        .orElseThrow(() -> new OrderNotFoundException(command.salesOrderHeaderId()));
    try { order.cancel(command.reason()); }
    catch (SalesOrder.OrderNotCancellableException e) { throw new OrderNotCancellableException(e); }
    salesOrders.save(order);
    sagaManager.requestCompensation(command.salesOrderHeaderId());
}

// reactive
@Transactional
public Mono<Void> cancel(CancelOrderCommand command) {
    return salesOrders.findById(SalesOrderId.of(command.salesOrderHeaderId()))
        .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(command.salesOrderHeaderId())))
        .flatMap(order -> {
            try { order.cancel(command.reason()); }              // sync domain call survives inside the lambda
            catch (SalesOrder.OrderNotCancellableException e) {
                return Mono.error(new OrderNotCancellableException(e));
            }
            return salesOrders.save(order);                      // Mono<Void>
        })
        .then(sagaManager.requestCompensation(command.salesOrderHeaderId())
            .onErrorMap(SalesOrderFulfilmentSagaManager.SagaNotFoundException.class,
                        e -> new SagaNotFoundException(command.salesOrderHeaderId())));
}
```

Recurring transformations across the 26 services: return types
(`Optional<T>`→`Mono<T>`, `List<T>`→`Flux<T>`); `.orElseThrow`→`.switchIfEmpty(Mono.error)`;
the sync domain call survives inside `.flatMap`; per-line I/O loops become a `Flux`
sub-pipeline (`placeOrder`'s line loop → `concatMap`, preserving `LineNumbering`
order); `Assert`/entry guards can no longer throw eagerly (move into `Mono.defer`
/ `Mono.error`); `@Transactional` → reactive tx; exception rewrapping →
`.onErrorMap`; `SagaManager`'s `TransactionTemplate`+`PROPAGATION_REQUIRES_NEW`
loop → `TransactionalOperator` + `Flux.concatMap`; all `*QueryPort`/`*Lookup`
ports gain `Mono`/`Flux`; unit tests → `StepVerifier`.

## B5. The one targeted win even here: WebClient in the BFF fan-out

The BFFs do **no JDBC** — stateless HTTP composers — so they're the one place
async helps without the cascade. Three outbound controllers, not equivalent:

| Controller | Today | Verdict |
|---|---|---|
| `SagaAggregatorController` (demo-bff) `list()`/`pump()` | **sequential** blocking loop over 3 services (`http.send`) | **Real win** — 3 round-trips in series → concurrent |
| `AuditAggregatorController` (erp-bff) `aggregate()` | **already concurrent** via JDK `sendAsync()` + `CompletableFuture` over 7 | **style only** — no throughput gain |
| `ProxyController` (both) `proxy()` | single blocking `send()` | **no win** — one call; leave as-is |

`WebClient` ships in `spring-webflux` but does **not** flip the app to WebFlux —
with both starters present Boot picks `WebApplicationType.SERVLET`. So you keep
Tomcat/servlet/`SseEmitter` and gain a non-blocking client. Sketch for the real
target:

```java
@GetMapping
public List<SagaRow> list() {
    return Flux.fromIterable(List.of("sales", "manufacturing", "purchasing"))
        .flatMap(this::fetchUpstream)          // concurrent
        .sort(Comparator.comparing(SagaRow::updatedAt,
            Comparator.nullsLast(Comparator.reverseOrder())))
        .collectList()
        .block();                              // block once at the servlet edge — pure non-blocking HTTP inside, no thread parked
}
```

**Lighter alternatives, no new dependency** (we're on Java 21): (1)
`HttpClient.sendAsync()` + `CompletableFuture` — exactly what the audit aggregator
already does, cheapest fix for the sequential regression; (2) **`RestClient`**
(already in `spring-web`) + **virtual threads** — fluent, synchronous, no
Reactor/Netty. Prefer these unless you specifically want non-blocking SSE-consume
(which WebClient unlocks: `bodyToFlux(ServerSentEvent<...>)`, letting the saga
aggregator subscribe to upstream `/stream` instead of polling at 1 s).

**Virtual threads here ≠ the service-wide switch.** The `RestClient` + virtual-
threads option above uses Loom only as a *vehicle for concurrent fan-out in a BFF*
— its payoff is lower fan-out latency for one request, and it's optional (the
`sendAsync()` route gets the same concurrency with no virtual threads at all). The
*headline* virtual-threads benefit — cheap high-concurrency blocking over JDBC —
comes from enabling them **on the eight services**, a separate change (next).

## B6. Turning on virtual threads (service side)

The service-wide scalability enabler, distinct from §B5's BFF fan-out use. On
**Java 21 + Spring Boot 4.0.5** it's a property, no code change — in each service's
`application.yml`:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

Boot then runs Tomcat with a virtual-thread-per-request executor and switches
`@Async`/`@Scheduled` executors to virtual threads, so a request thread blocked on
a JDBC socket read **unmounts its carrier** and high concurrency stays cheap.
Before flipping it on, clear two caveats (§A2):

- **Carrier pinning** — confirm the PostgreSQL JDBC driver version uses
  `ReentrantLock` (not `synchronized`) on its hot path, and audit any app/library
  `synchronized` block wrapping I/O; a pinned carrier defeats the benefit.
- **Connection-pool ceiling** — virtual threads make *waiters* cheap, not
  concurrent DB *work*; the win is real only if request threads (not the Hikari
  pool) are the bottleneck.

A low-priority item; for the showcase's
traffic, request-thread exhaustion never bites, so it's opt-in-when-measured.

---

## One-line recommendation

For Northwood: **skip reactive and effect systems.** Keep the eight services on
blocking JDBC; if connection-waiters ever get expensive, enable **virtual
threads** (`spring.threads.virtual.enabled=true`) — same effective scalability for
a connection-pool-bound workload, with synchronous code, no virality, and
`*Repository` staying in `domain/`. Independently, fix the
`SagaAggregatorController` sequential fan-out with `sendAsync()` or `RestClient`.
Reserve WebFlux + R2DBC or ZIO/CE + Skunk for a *different*, streaming-heavy /
extreme-concurrency service — not this one.
