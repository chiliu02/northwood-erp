# Northwood: the architecture

> **In short** — Northwood is six business services and one reporting service, each a Java 21 Spring
> Boot application that owns one PostgreSQL schema and publishes its changes as events through a
> transactional outbox to Kafka. A search service and two backends-for-frontends sit on the read side.
> Every service is layered hexagonally: `api → application → domain`, with `infrastructure` plugged in
> behind ports. A browser reaches it all through one backend-for-frontend that signs the user in with
> Keycloak.

**It builds on [`domain.md`](domain.md)**, cited as *domain n*. It uses the domain's glossary
(*domain 3*) for every business word. The deployment, configuration values and operations are in
[`infrastructure.md`](infrastructure.md), which builds on this document.

---

## 1. The boundary

**What is the system, and what is outside it? Northwood is the order-to-cash, procure-to-pay,
manufacturing and accounting back office of *domain 1*, exposed as REST and GraphQL APIs and one web
UI. It calls nothing outside itself except its own infrastructure: PostgreSQL, Kafka, Keycloak,
Redis and Elasticsearch.**

| Inside | Outside, called by Northwood | Outside, calling Northwood |
|---|---|---|
| 7 domain services (`product`, `sales`, `inventory`, `manufacturing`, `purchasing`, `finance`, `reporting`) | PostgreSQL 17: the system of record | A browser running the ERP SPA |
| `search-service` | Kafka: the event log | API clients holding a Keycloak token (Swagger UI, GraphiQL, the load test) |
| `erp-web-ui-bff`, `erp-graphql-bff` | Keycloak: identity and tokens | |
| `erp-web-ui` (the SPA) | Redis: sessions and cache | |
| `shared`, `shared-kernel`, 6 `*-events` modules | Elasticsearch: search indices | |
| `test-harness`, `load-test` (test only) | OTLP and Loki endpoints (telemetry, optional) | |

**Deliberately not done:**
- No synchronous call between domain services. The only contract between them is the outbox
  (section 5). A "quick REST call" from one service to another is a design fault.
- No integration with carriers, payment gateways or supplier systems (*domain 2.2*, *12*).
- No server-side rendering, localisation or e-mail. The API is locale-free (section 8).

---

## 2. The surfaces

**How is the system reached? Through REST per service, one browser-facing backend-for-frontend that
proxies those REST paths, a GraphQL backend-for-frontend, server-sent events for live activity, and
one Kafka topic per producing service.** Every service also exposes OpenAPI and actuator endpoints.

### 2.1 REST, per service

Each domain service publishes its resources under `/api/<resource>` (plural, kebab-case), with
commands as sub-paths (`POST /api/sales-orders/{id}/cancel`). OpenAPI is at `/v3/api-docs` and
Swagger UI at `/swagger-ui/index.html` on each service.

| Service | Port | Resources |
|---|---|---|
| product | 8081 | `products` |
| sales | 8082 | `customers`, `sales-orders`, `sagas` |
| inventory | 8083 | `stock-items`, `stock-reservations`, `stock-movements`, `goods-receipts`, `shipments`, `stock-adjustments` |
| manufacturing | 8084 | `boms`, `work-orders`, `products/{id}/materials-cost`, `sagas` |
| purchasing | 8085 | `suppliers`, `supplier-product-prices`, `purchase-requisitions`, `purchase-orders`, `sagas` |
| finance | 8086 | `customer-invoices`, `supplier-invoices`, `payments`, `journal-entries`, `exchange-rate` |
| reporting | 8087 | `sales-orders/{id}/360`, `atp`, `work-orders/{id}/board`, `material-shortages`, `purchase-orders/{id}/tracking`, `replenishment-history`, `financial-dashboard` |
| search | 8093 | `search/products` (+ `/suggest`), `search` (global), `search/audit` |

Every domain service also serves `GET /api/audit` (section 10.1).

### 2.2 The web backend-for-frontend (`erp-web-ui-bff`, 8089)

The SPA talks only to this BFF, with relative paths. The BFF:
- runs the OIDC authorization-code flow as the confidential client `northwood-bff`, and keeps the
  session and the user's tokens server-side (section 7);
- proxies every `/api/**` path to the owning service with the user's access token attached. A route
  table maps path prefixes to services. Where the reporting and command sides would share a path,
  the command side takes a `-cmd` prefix (`/api/sales-cmd`, `/api/work-orders-cmd`,
  `/api/purchase-orders-cmd`, `/api/products-cmd`), which the BFF rewrites;
- composes two endpoints itself: `GET /api/audit` (fans out to every service and merges by time) and
  `GET /api/events` (server-sent events of every domain event, read from Kafka).

### 2.3 The GraphQL backend-for-frontend (`erp-graphql-bff`, 8088)

One typed graph over the REST services, at `/graphql` (GraphiQL at `/graphiql`). It batches per type
with DataLoader, so a list of N orders makes one upstream call per type, not N. It offers a
`placeOrder` mutation, a `search` query that finds in Elasticsearch and then fetches each hit fresh
from its owning service (section 11.3), and an `events` subscription over WebSocket fed from Kafka.

### 2.4 Events

Each producing service publishes to one topic, `<service>.events`, with dead-letter companions
`<service>.events.dlt` and `<service>.events.dlt.parked`. The record is a JSON `EventEnvelope`
(section 5.2). Each payload type is a Java record in the producer's `<service>-events` module, which
consumers compile against. The domain events are listed in *domain 8*.

---

## 3. The building blocks, and the rules that are enforced

**What are the parts, and what may depend on what? A Maven reactor of service modules, an events
module per producer, a Spring-free kernel and a shared infrastructure module. Inside each service,
four layers with a strict dependency rule.** The rules are what keep each service splittable and
each domain word in one place.

### 3.1 Modules

| Module | Holds | May depend on |
|---|---|---|
| `shared-kernel` | Spring-free values and helpers every layer may use: `Money`, `Quantity`, `Sku`, `Currencies`, `Assert`, `DomainEvent`, `LineNumbering`, the Saga base row `SagaInstance` | nothing |
| `shared` | Messaging, inbox/outbox, Saga harness, security, error model, audit, observability, Liquibase wiring. Split into `shared.application.*` (ports, abstract bases), `shared.infrastructure.*` (adapters, auto-configuration), `shared.api.*` (audit controller, error advice, role annotations) | `shared-kernel` |
| `<service>-events` (6) | The producer's event records, the values they carry, the `<Service>AggregateTypes` constants and any wire-format status constants | `shared-kernel` (and another producer's events module when an event is stamped with that producer's aggregate type) |
| `<service>-service` (7) | One bounded context (*domain 2.1*), or the reporting views | `shared`, its own events module, the events modules of producers it consumes |
| `search-service` | Elasticsearch read models | `shared`, all events modules |
| `erp-web-ui-bff`, `erp-graphql-bff` | Browser-facing edges | Spring web, security, Kafka (no `shared`) |
| `erp-web-ui` | The React SPA | the BFF over HTTP |
| `test-harness` | The acceptance test bus and in-memory doubles of every port | all services |
| `load-test` | Gatling load simulation | the BFF and services over HTTP |

**A service never depends on another `*-service` module.** The events module is the only thing that
crosses a boundary. *Reason:* the consumer always depends on the producer's event shape; making that
dependency a jar turns a producer's breaking change into a consumer compile error instead of a
runtime deserialisation failure.

### 3.2 The four layers inside a service

```
<service>/src/main/java/com/northwood/<context>/
├── api/              @RestController + api/dto (only when the wire shape differs)
├── application/      use cases, ports, application/dto (*View, *Command), application/inbox (*Handler, *Projection), application/saga
├── domain/           aggregates, values, the storage interfaces of aggregate roots, domain/saga
└── infrastructure/   persistence (Jdbc*), saga (Jdbc*SagaManager, *SagaWorker), messaging, metrics, cache …
```

| Layer | May import | May not import | Why |
|---|---|---|---|
| `api` | `application` | `domain`, `infrastructure` | The application layer is the only seam to the rest of the system; a controller that sees an aggregate can change it without the use case's rules. |
| `application` | `domain`, other `application`, `shared-kernel`, `shared.application` | `api`, `infrastructure` | Use cases state intent through ports; the mechanism stays swappable. |
| `domain` | `shared-kernel` | everything else | The model is pure and testable with no framework. |
| `infrastructure` | `application`, `domain`, `shared-kernel`, `shared.*` | `api` | Adapters implement ports; they never drive controllers. |

**One exception:** an `@AutoConfiguration` class is a layer-spanning factory, so the shared
`AuditAutoConfiguration` may import `shared.api.audit.AuditController` to register it. No other class
may do this.

**`JdbcTemplate` never appears in `application`.** Every database call goes through a port declared in
`application` (or a storage interface declared in `domain`) and implemented by a `Jdbc*` class in
`infrastructure/persistence`.

### 3.3 Port vocabulary

| Role | Interface | Implementation |
|---|---|---|
| Storage of an aggregate root (*domain 6*) | `*Repository` (in `domain`) | `Jdbc*Repository` |
| Messaging and Saga machinery | `*Port` | `Jdbc*Adapter` |
| Read of whole rows or lists for a view | `*QueryPort` | `Jdbc*QueryPort` |
| One narrow value | `*Lookup` | `Jdbc*Lookup` |
| Write to a read model from an inbound event | `*Projection` (in `application/inbox`) | `Jdbc*Projection` |
| Write to a running total (*domain 5.7*) | `*Writer` | `Jdbc*Writer` |

**A `*Repository` exists only for an aggregate root** that declares `AGGREGATE_TYPE` and guards an
invariant. *Reason:* a total stored behind a repository can be changed apart from the facts that
make it (*domain 5.7*).

### 3.4 How the rules are checked

The layer rules are checked by these searches over the source, run in code review. Each must return
the count shown:

```
'^import com\.northwood\.\w+\.domain\.'         in **/api/**/*.java            → 0
'^import com\.northwood\.\w+\.infrastructure\.' in **/api/**/*.java            → 0
'^import com\.northwood\.\w+\.api\.'            in **/application/**/*.java    → 0
'^import com\.northwood\.\w+\.infrastructure\.' in **/application/**/*.java    → 0
'^import com\.northwood\.\w+\.(api|application|infrastructure)\.' in **/domain/**/*.java → 0
'^import com\.northwood\.\w+\.api\.'            in **/infrastructure/**/*.java → 1 (the audit auto-configuration)
'JdbcTemplate'                                   in **/application/**/*.java    → 0
'^import com\.northwood\.manufacturing\.'       in purchasing-service          → 0
```

The Saga state lists are checked at start-up (section 6.4). Making the layer rules a build-time test
is open (section 16).

---

## 4. The runtime view

**What runs, and how does one piece of work pass through? Each service runs a few scheduled loops and
one Kafka listener next to its HTTP handlers. A command commits its state and its events in one
transaction; everything after that is asynchronous.**

### 4.1 What runs without a request

| Loop | Where | Cadence | Does |
|---|---|---|---|
| Outbox drain | every producer | every `northwood.outbox.poll-interval` (1 s) | Publishes pending outbox rows to Kafka (section 5.1). |
| Inbox dispatcher | every consumer | Kafka listener, one thread per partition | Decodes each envelope and passes it to every handler of its event type (section 5.4). |
| Dead-letter redriver | every consumer | Kafka listener on `.+\.dlt` | Re-applies this service's dead-lettered records, then parks the ones that still fail (section 5.5). |
| Saga worker | sales, manufacturing, purchasing | every `northwood.saga.poll-interval` (1 s) | Claims due Saga rows and advances them (section 6). |
| Dashboard balance refresh | reporting | every 60 s | Recomputes the account balances on the financial dashboard. |
| Event fan-out | both BFFs | Kafka listener | Pushes every event to SSE and GraphQL subscribers. |

### 4.2 One command, end to end: placing a sales order

1. The SPA posts to the BFF, which forwards the request to `sales` with the user's token.
2. `SalesOrderController` checks the role (`sales_clerk`) and calls `SalesOrderService.placeOrder`.
3. In one transaction, the service builds the `SalesOrder` aggregate, inserts the fulfilment Saga row
   at `started`, and saves. `JdbcSalesOrderRepository.save` writes the header, the lines, and one
   outbox row for each pending event (`SalesOrderPlaced`). The request returns `201`.
4. The sales outbox drain publishes `SalesOrderPlaced` to `sales.events`, keyed by the order's id.
5. The fulfilment Saga worker claims the new row and appends `StockReservationRequested` to the
   outbox (or parks the Saga behind the planning time fence or the prepayment gate).
6. Inventory's inbox handler for `StockReservationRequested` reserves stock and, for any short line,
   raises a `ReplenishmentRequest` in the same transaction, then appends `StockReserved`.
7. Sales' inbox handler applies `StockReserved` to the Saga. Reporting and search update their read
   models from the same events.

Every step after 3 is a new local transaction in one service, started by an event. Nothing waits for
another service to answer.

---

## 5. Messaging

**How do services exchange facts? Each one writes its events to an outbox table in the same
transaction as its state change. A drain publishes them to Kafka, keyed by aggregate. Each consumer
records what it has applied in an inbox table, so a redelivered event changes nothing.** The
database, not Kafka, is the system of record.

### 5.1 The outbox

- An aggregate collects its events in `pendingEvents`. `repository.save()` drains them into the
  outbox in the same transaction as the state change. *Reason:* the state change and its event commit
  together or not at all (*domain 5.7*'s rule, applied to messages).
- A Saga or a handler with no aggregate to change appends through `OutboxAppender` /
  `OutboxPort.appendPending(OutboxRow.pending(...))`.
- `OutboxDrainer.drain()` selects `status IN ('pending','failed') ORDER BY sequence_number` in batches
  of 100, with `FOR UPDATE SKIP LOCKED`, inside one transaction. It sends each row and waits for the
  broker's acknowledgement before marking it `published`. A failed send marks the row `failed`; the
  next tick tries again. *Reason for draining by status, not by a high-water mark:* a transaction that
  takes a low sequence number but commits late is still `pending` on a later tick, so it is never
  skipped.
- The drainer and its scheduler are two beans. *Reason:* the drain's transaction (and its row locks)
  applies only through the Spring proxy, so the scheduler must call it from outside.
- The event's id is the outbox row's id, the envelope's `eventId`, and the inbox's `message_id`.

### 5.2 The envelope

`EventEnvelope` carries `eventId`, `aggregateType`, `aggregateId`, `eventType`, `eventVersion`,
`payloadJson`, `correlationId`, `causationId`, `actorUserId`, `occurredAt`, and a header map. The
headers hold the `source-service` (which names the topic) and the `traceparent`. `eventType` is `<service>.<EventClass>` and is
always read from the event's `EVENT_TYPE` constant; `aggregateType` from the root's `AGGREGATE_TYPE`.

**An event is stamped with the aggregate the fact is about**, not with the module that emits it.
Cross-context stamping is allowed when the subject is that aggregate, the emitter is the natural
source of the fact, and the emitter claims none of the aggregate's invariants. Two events do this:
`ProductMaterialsCostComputed` (emitted by manufacturing, stamped `Product`) and both
`ReplenishmentUndispatchable` events (emitted by manufacturing and purchasing, stamped
`ReplenishmentRequest`).

**Event classes are the navigation anchor.** Every emitter and consumer names an event through its
class or its constants, never a string. *Reason:* Find Usages on an event class then lists every
producer and consumer, a consumer's imports list every event it reacts to, and a rename fails the
build at every dependent site. Two events with the same name in different contexts are two classes.

### 5.3 Topics and ordering

- One topic per producing service, never per event type. Each producer declares its topic and its
  two dead-letter companions at start-up; the broker never creates a topic by itself.
- **The partition key is the aggregate id.** This gives one guarantee: events of one aggregate are
  consumed in the order they were written. Order across aggregates and across topics is not kept.
- Every assumption about order sits in one of three buckets:
  - **guaranteed**: same aggregate (same partition), or causal (the prerequisite was committed before
    the event that depends on it was emitted; a Saga row is always created before the request whose
    answer it waits for);
  - **guarded**: a handler that would be wrong out of order checks a source state or a terminal and
    does nothing;
  - **don't care**: the write converges in any order (`INSERT … ON CONFLICT DO UPDATE`, atomic
    `col = col ± ?`).

  An assumption in none of the three is a defect.
- A single change that must happen once belongs in one handler, not split across two consumers of
  differently keyed events.

### 5.4 The inbox: applied once per handler

- `KafkaInboxDispatcher` is the one `@KafkaListener` per service, subscribed to the topics in
  `northwood.kafka.subscribe-topics`. It passes each envelope to every `AbstractInboxHandler` whose
  event type matches. An event type with no handler is ignored, so subscribing to a whole topic is
  harmless. **Adding a handler for a new producer also means adding that producer's topic to the
  subscription.**
- `AbstractInboxHandler.handle()` runs in one transaction: check `(eventId, handlerName)` in the
  inbox, apply, record. If the apply throws, the record rolls back with it and the event can be
  applied again later. The key is per handler, so one event fanned out to N handlers lands N times,
  once each.
- The check is a strategy, `northwood.inbox.dedup-strategy`. The default, `advisory-lock`, takes
  `pg_advisory_xact_lock(hash(eventId:handlerName))` in one statement, then checks existence in a
  second statement. *Reason for two statements:* under `READ COMMITTED`, a check folded into the lock
  statement would use a snapshot from before the wait and miss the row the first holder committed.
  The portable `unique-claim` strategy is reserved; selecting it stops start-up.
- A malformed envelope is logged and skipped, and its offset is committed. It is the one failure that
  neither retries nor dead-letters.

### 5.5 Failure handling

- The listener's error handler classifies each exception. A transient one is retried under an
  exponential back-off (1 s doubling to 30 s, at most 5 minutes in total). A deterministic one (bad
  payload, constraint, null) goes to the dead-letter topic on the first failure.
- The dead-letter record keeps the aggregate id as its key and is placed by key hash, so a
  dead-letter topic may have any number of partitions.
- `DltRedriver` (group `<service>-dlt-redriver`) reads every `.dlt` topic, re-applies only the records
  its own service dead-lettered (by the `kafka_dlt-original-consumer-group` header), up to 5 attempts,
  10 s apart. The records that still fail go to `<topic>.dlt.parked`, which nothing consumes. It
  catches every failure itself, so no `.dlt.dlt` exists.
- Kafka's loss is recoverable: re-set outbox rows from `published` to `pending` and they are sent
  again; the inbox absorbs the duplicates.

### 5.6 Traces across hops

Each emitter stores the current W3C `traceparent` in the outbox row's headers. The drain adds it as a
span *link* on its `outbox.publish` span, and Kafka's producer and listener observations carry the
context to the consumer. Each hop is its own bounded trace, linked to the one that caused it.
*Reason:* making every hop a child of the first request would give traces without end; links keep
each trace small and still navigable.

---

## 6. Sagas in code

**How is a long-running process (*domain 9.2*) built? As a row in its owner's schema, advanced by a
scheduled worker for steps it starts and by inbox handlers for steps others answer, with one class
holding the state machine.**

### 6.1 The parts

| Part | Where | Does |
|---|---|---|
| `SagaInstance` | `shared-kernel` | The Saga as data: state, step, JSON data, lease, retry, version. Intent-named mutators (`transitionTo`, `scheduleRetry`, `parkUntil`, lease methods). |
| `<Flow>Saga` | `<service>/domain/saga` | The state constants, `ALL_STATES`, the domain key, and the `apply*` transitions. |
| `SagaPort` / `Jdbc<Flow>SagaAdapter` | `shared.application.saga` / `infrastructure/saga` | `claimDue`, `insert`, `update`, `findBySagaId`. |
| `SagaManager` / `Jdbc<Flow>SagaManager` | `shared.application.saga` / `infrastructure/saga` | The polling harness, plus one `apply*` per inbound event. Holds no outbox, no `JdbcTemplate`, no other service. |
| `<Flow>SagaWorker` | `infrastructure/saga` | `@Scheduled` poll; its `advance` switches on state and emits the next command. |
| Inbox handlers, emitters | `application/inbox`, `application` | Parse the event, call the manager with plain values, act on the state it returns. |

### 6.2 Claiming and advancing

- `claimDue` is one `UPDATE … WHERE saga_id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING`, over
  active states whose `next_retry_at` has passed and whose lease is free or expired (30 s). *Reason:*
  any number of workers on any number of instances can poll safely.
- Each Saga in a batch advances in its own `REQUIRES_NEW` transaction, so one failure rolls back only
  its own outbox writes. On failure, a fresh transaction reloads the row and schedules a retry (15 s),
  so no half-applied change is saved.
- `update` checks `version`; a losing concurrent change raises an optimistic-lock failure and is
  retried.
- An `apply*` that finds no Saga throws. *Reason:* the Saga is always created first (section 5.3), so a
  missing row is a real fault for the dead-letter path, not a timing race.
- Each `apply*` checks its source state and does nothing from any other state.

### 6.3 Timers: decide once

A timed wait (planning time fence, prepayment back-stop) uses `parkUntil(instant)`, which writes
`next_retry_at`; the claim wakes the row. The entry state decides once, using an injected
`InstantSource`, whether to emit now or park. The wait state emits without checking the clock again.
*Reason:* the Java clock decides whether to park and the database clock decides when to wake; checking
both can re-park a Saga forever if they disagree.

### 6.4 Guards on the state lists

`SagaStateInvariantChecker` compares each Saga's `ALL_STATES` with its table's `saga_state` CHECK at
start-up and stops the service on any difference. A Saga lists only states its code writes.

### 6.5 Compensation

The fulfilment Saga's data carries three joins of the same shape: the short lines waiting for
replenishment, the completion gate (shipped and settled), and the outstanding compensation legs. Leg
ids are `<target>:<salesOrderLineId>`. A duplicate or out-of-order answer is a no-op, because removing
a leg from a set is idempotent and order-free. Inventory lists the legs, because it owns the map from
an order-pegged line to its work order or purchase order; the Saga counts the answers.

### 6.6 Milestones

Every state change records a one-span trace named `saga.<state>`, tagged `northwood.saga_id`,
`northwood.saga_type` and, where the Saga has one, `northwood.sales_order_id`, and linked to the trace
that caused it. The adapter is the only place this happens. *Reason:* a Saga lasts longer than a trace
should; a query on `saga_id` shows its whole history as milestones.

---

## 7. Identity and authorisation

**Who is the caller, and what may they do? Keycloak signs users in. The web BFF holds the session and
tokens. Every service is a resource server that checks a JWT and a realm role per command.**

- **Realm `northwood`** holds the roles of *domain 3.8* as realm roles (`catalog_manager`,
  `sales_clerk`, `sales_manager`, `warehouse_clerk`, `warehouse_manager`, `production_planner`,
  `production_supervisor`, `purchasing_clerk`, `purchasing_manager`, `accountant`,
  `finance_manager`, `auditor`), plus `sysadmin` for realm administration only.
- **The BFFs** are the confidential client `northwood-bff` (authorization-code flow). The session, and
  the OAuth2 authorized client that holds the tokens, live in Redis through Spring Session (30 min).
  *Reason:* a BFF restart keeps users signed in, and more than one BFF instance can share a session.
  `/api/**` answers an unauthenticated call with `401` so the SPA can send the browser to the sign-in
  page; a redirect would be followed inside `fetch` and fail. The GraphQL BFF copies the token onto
  each upstream call, including calls made by asynchronous resolvers.
- **Services** use `OAuth2ResourceServerSecurityConfig` (in `shared`, active when
  `spring.security.oauth2.resourceserver.jwt.issuer-uri` is set). `KeycloakRealmRoleConverter` maps
  `realm_access.roles` on the access token to Spring authorities. *Reason:* Keycloak puts realm roles
  on the access token, not the ID token.
- **Each command** carries one role annotation from `shared.api.security` (`@RequireSalesManager`,
  …), each a meta-annotation over `@PreAuthorize("hasRole('…')")`. *Reason:* the role string exists in
  one place. Reads need only an authenticated caller. The map from command to role is *domain 3.8*.
- **The actor** of every write is read by `CurrentUserAccessor` and stored as the outbox row's
  `actor_user_id`.
- `/actuator/health` and `/actuator/prometheus` are open, for health checks and scraping.

---

## 8. The error model

**What does a caller get when a request fails? A typed `ErrorResponse { code, params }` with the
status that matches the failure, from one shared advice. No English message is part of the
contract.**

- Every application exception implements `DomainException` and extends one of `NotFoundException`
  (404), `ConflictException` (409) or `BadRequestException` (400). Each concrete class has its own
  `CODE`. A domain exception is wrapped in an application exception before it leaves the application
  layer.
- `DomainExceptionAdvice` (shared, auto-configured) maps them. No controller declares its own
  `@ExceptionHandler`.
- A stray `IllegalArgumentException` / `IllegalStateException` from `Assert` maps to
  `GENERIC_ARGUMENT_VIOLATION` (400) / `GENERIC_STATE_VIOLATION` (409) with the message in `detail`.
  A check the SPA must act on gets its own exception and code instead.
- **The backend is locale-free.** No `MessageSource`, `ResourceBundle` or `Accept-Language`. *Reason:*
  every user-visible string is rendered in the SPA, so the SPA owns translation and the API answer is
  the same for every caller.
- Arguments and states are checked with `shared.domain.Assert` (`notNull`, `notBlank`, `notEmpty`,
  `argument`; `state`, `stateNotNull`, …), never with an inline `throw` of `IllegalArgumentException`
  / `IllegalStateException` or `Objects.requireNonNull`. *Reason:* one exception type per kind of
  check, and every `not*` returns its argument for one-line field assignment.
- Inputs are checked first at the API, then in the application, then in the domain. A database
  CHECK is the last line, never the only one. *Reason:* a CHECK violation inside an inbox handler is
  a deterministic failure that dead-letters the event and wedges the Saga behind it.

---

## 9. How configuration is read

**Where does a setting come from? From `application.yml` in each module, with every value an operator
may change written as `${ENV_VAR:default}`, and from Spring profiles.** The list of settings and their
values is in *infrastructure 3*.

- Profile `dev` is the default: the outbox fills but nothing is published or consumed. Profile `kafka`
  loads `application-kafka.yml`, which turns on publishing (`northwood.outbox.drain.enabled`), the
  listener (`northwood.kafka.subscribe-topics`) and the dead-letter redriver. *Reason:* a service can
  run alone with no broker.
- Shared behaviour is wired by `@AutoConfiguration` classes in `shared`, each gated on a property or a
  missing bean, so a test can put in its own double.
- `northwood.service-name` names the service's topic and the source of its events.
- Each pool runs `SET search_path TO <service>, shared` on every connection (section 12.1).

---

## 10. What the system records

**What does the system keep about what happened? Every event, in the outbox; every status change of
the main documents, in history tables; and traces, metrics and logs for every request and hop.**

### 10.1 The audit trail

The outbox is the audit trail: each row has the aggregate, the event type, the actor, the time and the
trace id. `GET /api/audit?aggregateId=&from=&to=&limit=` on each service reads its own outbox, without
the payload. The BFF merges all services into one timeline. Every event is also indexed in
Elasticsearch (section 11.2) for search. *Reason:* the record of what changed is written in the same
transaction as the change, so it cannot be missing.

`sales_order_status_history`, `work_order_status_history`, `purchase_order_status_history`,
`invoice_status_history` and `payment_status_history` keep each status change of their documents.

### 10.2 Telemetry in code

- `shared` brings Micrometer with the Prometheus registry, `spring-boot-starter-opentelemetry` (the
  Spring Boot 4 tracer starter, without which no `Tracer` bean exists), and the Loki logback appender.
  `shared/src/main/resources/logback-spring.xml` writes to the console and to Loki at `LOKI_URL`,
  with the service name, level and trace id on every line.
- Scheduled-task and Spring Security observations are off. *Reason:* the 1 s loops would flood the
  trace store with empty spans; the real work spans (`outbox.publish`, `saga.<state>`) are made by
  hand.
- The BFFs make no spans of their own (sampling 0); each service starts its own traces.
- Custom metrics: `northwood.outbox.pending` per service and `northwood.saga.count` per Saga state.

---

## 11. The read side

**How is data read across services? Three ways, all fed by the same events: SQL views in reporting,
search indices in Elasticsearch, and a GraphQL layer that finds in the index and shows fresh state from
the owning service.** Every read model can be dropped and rebuilt from the events.

### 11.1 Reporting

`reporting-service` keeps the views of *domain 11* in its own schema. Each view has a `*Projection`
fed by inbox handlers, written as upserts that converge in any order, and a `*QueryPort` for reads.
The hot views (available-to-promise, financial dashboard, sales order 360) are cached in Redis
(cache-aside, 60 s time-to-live). The projection that changes a row evicts its cache key after the
transaction commits. *Reason for evicting from the projection, not the handler:* the handler calls its
own `apply` inside the same bean, which bypasses Spring's cache proxy. A Redis failure falls back to
PostgreSQL, never to an error.

### 11.2 Search

`search-service` has no database and no inbox. It consumes every `*.events` topic in its own group
from the earliest offset and keeps three indices: `products` (fuzzy text, facets, autocomplete),
`entities` (one index for customers, sales orders, suppliers, purchase orders, invoices and products,
with a type field) and `audit-events` (every event). *Reason for no inbox:* each document is written
by its id, so applying an event twice gives the same document.

### 11.3 Find, then hydrate

The GraphQL `search` query takes its ranking from Elasticsearch and then fetches each hit from its
owning service. *Reason:* the ranking may lag behind; the values shown may not. Authoritative state of
one entity always comes from its owning service.

---

## 12. The data model

**How is the data stored? In one PostgreSQL database with one schema per service, a `shared` schema
for functions, and no foreign key that crosses a schema. Each service connects as its own role and can
see only its own schema.** The baseline schema is one SQL script; it is the reference for every
column.

### 12.1 Schema per service

- Schemas `product`, `sales`, `inventory`, `manufacturing`, `purchasing`, `finance`, `reporting`, and
  `shared` (functions only: `uuid_generate_v7()`, `set_updated_at()`).
- Each service has a `NOLOGIN` role `<service>_service` with `USAGE` on its own schema and `shared`,
  and `SELECT, INSERT, UPDATE` on its own tables. No role may `DELETE`, and none may read another
  schema.
- Each pool sets `search_path = <service>, shared`, and SQL names tables without a schema. A wrong
  table name therefore fails; it is never fixed by naming another schema.
- **No foreign key crosses a schema.** A reference to another context's aggregate is a bare UUID,
  kept true by events (*domain 2.2*, rule 3).
- *Reason:* moving a service to its own database changes only its `spring.datasource.url`.

### 12.2 Conventions

- Singular table names. A master-detail parent is `<name>_header` only when its child is
  `<name>_line`; otherwise both are bare (`work_order` + `work_order_material`). The key of a table is
  `<table>_id`; a foreign key is `<referenced table>_id`.
- UUID v7 keys. `version BIGINT` on every changeable aggregate root for optimistic locking (an update
  that matches no row raises `OptimisticLockingFailureException`). `created_at` / `updated_at`
  (`set_updated_at()` trigger), `created_by` / `last_modified_by`.
- Money is `NUMERIC(18,2)` with a `currency_code`. A transaction header (sales order, purchase order,
  invoices, payment, journal entry) also stores its `exchange_rate` and the time it was taken. A
  catalogue price is in the base currency and stores no rate.
- Every enumerated column has a CHECK that lists exactly the values its Java enum produces. The enum
  is nested in its aggregate, maps through `code()` / `fromCode()`, and never through `name()`.
- Logs that only grow are range-partitioned with a default partition: `outbox_message`
  (`created_at`), `inbox_message` (`processed_at`), `stock_movement` (`movement_date`),
  `journal_entry_line` (`posting_date`), and the status-history tables (`changed_at`).

### 12.3 Tables, by schema

Kinds: **A** aggregate root, **C** child of an aggregate, **S** Saga, **V** view or total (written from
events or by a writer port), **R** reference data, **M** messaging, **H** history.

| Schema | Tables |
|---|---|
| product | `product` A, `approved_vendor` C, `unit_of_measure` R |
| sales | `customer` A, `sales_order_header` A, `sales_order_line` C, `sales_order_fulfilment_saga` S, `product_card` V, `sales_order_status_history` H |
| inventory | `stock_reservation_header` A, `stock_reservation_line` C, `goods_receipt_header` A, `goods_receipt_line` C, `shipment_header` A, `shipment_line` C, `stock_adjustment` A, `replenishment_request` A, `stock_balance` V, `wip_balance` V, `stock_movement` V (append-only log), `product_card` V, `sales_order_line_facts` V, `purchase_order_line_facts` V, `warehouse` R |
| manufacturing | `bom_header` A, `bom_line` C, `work_order` A, `work_order_material` C, `work_order_operation` C, `routing_header` + `routing_operation` (template), `work_order_saga` S, `work_center` R, `product_card` V, `product_approved_vendor` V, `production_report` V, `work_order_status_history` H |
| purchasing | `supplier` A, `supplier_product_price` A, `purchase_requisition_header` A, `purchase_requisition_line` C, `purchase_order_header` A, `purchase_order_line` C, `purchase_to_pay_saga` S, `product_card` V, `product_approved_vendor` V, `purchase_order_status_history` H |
| finance | `customer_invoice_header` A, `customer_invoice_line` C, `supplier_invoice_header` A, `supplier_invoice_line` C, `payment` A, `payment_allocation` C, `journal_entry_header` A, `journal_entry_line` C, `gl_account` R, `tax_code` R, `exchange_rate` R, `product_card` V, `work_order_wip` V, `purchase_order_line_facts` V, `invoice_status_history` H, `payment_status_history` H |
| reporting | `sales_order_360_view`, `available_to_promise_view`, `production_planning_board`, `material_shortage_view`, `purchase_order_tracking_view`, `replenishment_history_view`, `customer_dashboard_status`, `financial_dashboard_daily`, `product_card` (all V), `projection_checkpoint` |
| every service but reporting | `outbox_message` M, `inbox_message` M (reporting has the inbox only) |

The `*_facts` tables are a consumer's own copy of another context's line facts, used to check a
command without asking that context: inventory gates shipments and goods receipts on them, and
finance matches supplier invoices against them.

### 12.4 Constraints that carry a domain rule

| Rule | How the schema holds it |
|---|---|
| Debits equal credits (*domain 5.6.1*) | Deferred constraint trigger `trg_journal_line_balance` (`enforce_journal_balance`) checks each journal entry at commit, and again when it is posted. |
| A posted journal entry, invoice or payment never changes (*domain 5.6.3*) | `guard_journal_immutability`, `guard_journal_line_immutability`, `guard_customer_invoice_immutability`, `guard_supplier_invoice_immutability`, `guard_payment_immutability` triggers. |
| Paid amount is the total of the allocations (*domain 5.6.5*) | `maintain_allocation_totals` trigger keeps `payment.amount_allocated` and the invoices' `paid_amount` in step. `outstanding_amount` is a generated column. |
| Available is on-hand minus reserved (*domain 5.3.1*) | `stock_balance.available_quantity` is a generated column. |
| At most one open replenishment request (*domain 5.3.10*) | Partial unique index `uq_replenishment_request_open` on `(product_id, warehouse_id)` where status is open and the reason is not per-line. A racing duplicate gets a duplicate-key error, which detection treats as "already open". |
| One active BOM and one active routing per product (*domain 5.4.1*) | Partial unique indexes `uq_bom_active_per_product`, `uq_routing_active_per_product`. |
| A reservation has exactly one source; a requisition's source matches its type | Table CHECKs. |
| A Saga is in a known state | `saga_state` CHECK equal to the Saga's `ALL_STATES` (section 6.4). |

The CHECK is the last line of defence. Every rule above is also checked in Java first (section 8).

### 12.5 The messaging tables

Every service's `outbox_message` and `inbox_message` have the same columns.

**`outbox_message`** (key `(outbox_message_id, created_at)`; partial index on `sequence_number` where
`status IN ('pending','failed')`)

| Column | Type | Meaning |
|---|---|---|
| `outbox_message_id` | UUID | The event's id; becomes the envelope's `eventId`. |
| `sequence_number` | BIGINT | Order of writing within the service. |
| `aggregate_type`, `aggregate_id` | VARCHAR(100), UUID | The aggregate the fact is about; the id is the partition key. |
| `event_type`, `event_version` | VARCHAR(150), INT | `<service>.<EventClass>`, version 1 by default. |
| `payload`, `headers` | JSONB | The event; `headers` holds the `traceparent`. |
| `correlation_id`, `causation_id` | UUID | Conversation and cause, when known. |
| `status` | VARCHAR(30) | `pending`, `published`, `failed`. |
| `retry_count`, `last_error` | INT, TEXT | Publish failures. |
| `created_at`, `published_at` | TIMESTAMPTZ | |
| `actor_user_id` | VARCHAR(64) | Who caused it; empty for Saga-driven events. |

**`inbox_message`** (key `(inbox_message_id, processed_at)`; unique `(message_id, handler_name,
processed_at)`)

| Column | Type | Meaning |
|---|---|---|
| `message_id`, `handler_name` | UUID, VARCHAR(150) | The dedup pair. |
| `event_type`, `event_version`, `source_sequence_number` | | From the envelope. |
| `payload` | JSONB | The event as applied. |
| `status` | VARCHAR(30) | `processed`, `failed`. |
| `processed_at` | TIMESTAMPTZ | Partition key. |

The partition key must be part of every unique constraint, so the unique constraint cannot be the
dedup gate by itself; the advisory lock is (section 5.4).

**`<flow>_saga`**: `saga_id`, the domain key (unique), `saga_state` (CHECK), `current_step`, `data`
(JSONB), `retry_count`, `last_error`, `next_retry_at`, `lease_owner`, `lease_expires_at`, `version`,
`trace_id`, `created_at`, `updated_at`, `completed_at`. The work-order and purchase-to-pay Sagas also
carry the originating sales order, where there is one.

### 12.6 Schema lifecycle

The baseline script holds the whole schema, the roles and the grants. The demo data is a second script
with fixed UUIDs, so references across schemas line up without a join. Liquibase is wired by hand
(`shared.infrastructure.db.LiquibaseConfig`, because Spring Boot 4.0.5 has no Liquibase
auto-configuration) with a master change log per service and its own change-log tables in its own
schema. It is switched off (`northwood.liquibase.enabled: false`), and a schema change is made in the
baseline script. *Infrastructure 6.3* covers applying a change to a running database.

---

## 13. What the code makes at runtime

**What does the system create when it starts or runs? Kafka topics and consumer groups, Elasticsearch
indices, Redis entries, and rows in its own tables. It makes no files and starts no processes.**

| Created | By | When |
|---|---|---|
| Topics `<service>.events`, `.events.dlt`, `.events.dlt.parked` (3 partitions, replication 1 unless set) | Each producer's `KafkaAdmin`, from `NewTopic` beans | Start-up, with the `kafka` profile. Only adds partitions, never removes them. |
| Consumer groups `<service>` (inbox) and `<service>-dlt-redriver` | Each consumer | First poll |
| Elasticsearch indices `products`, `entities`, `audit-events` | `search-service` | Start-up, if missing |
| Redis session keys (Spring Session) | Both BFFs | Sign-in; expire after 30 min idle |
| Redis cache entries for the reporting views | `reporting-service` | First read; 60 s time-to-live |
| Saga rows | `SalesOrderService.placeOrder`, PO creation, work-order release | In the transaction that starts the process |

---

## 14. The code map

**Where does each domain word live? Each aggregate of *domain 6* is a class of the same name at the
root of its service's `domain` package, with its storage interface beside it and the `Jdbc*`
implementation in `infrastructure/persistence`.** Aggregates are not put in sub-packages. *Reason:*
the events module shares the `domain` package path, so sub-packages would add an import for every
event and value the aggregate uses.

| Subdomain | Aggregates (`domain/`) | Application services (`application/`) | Controllers (`api/`) |
|---|---|---|---|
| Product | `Product` | `ProductService` | `ProductController` |
| Sales | `Customer`, `SalesOrder`, `saga/SalesOrderFulfilmentSaga` | `CustomerService`, `SalesOrderService`, `saga/SalesOrderFulfilmentSagaManager`, `SalesOrderCompensationEmitter`, `SalesOrderReadyToShipEmitter`, `SalesOrderUpfrontPaymentSettledEmitter` | `CustomerController`, `SalesOrderController`, `SagaApiController` |
| Inventory | `StockReservation`, `GoodsReceipt`, `Shipment`, `StockAdjustment`, `ReplenishmentRequest` | `StockReservationService`, `GoodsReceiptService`, `ShipmentService`, `StockAdjustmentService`, `StockItemService`, `ReplenishmentDetectionService` | `StockItemController`, `StockReservationController`, `StockMovementController`, `GoodsReceiptController`, `ShipmentController`, `StockAdjustmentController` |
| Manufacturing | `Bom` (+ `BomCycleDetector`), `WorkOrder`, `Routing` (template), `saga/WorkOrderSaga` | `BomService`, `BomViewService`, `WorkOrderReleaseService`, `WorkOrderOperationService`, `WorkOrderCancellationService`, `WorkOrderPrioritisationService`, `MaterialsCostRollupService`, `saga/WorkOrderSagaManager` | `BomController`, `WorkOrderController`, `ProductMaterialsCostController`, `SagaApiController` |
| Purchasing | `Supplier`, `SupplierProductPrice`, `PurchaseRequisition`, `PurchaseOrder`, `saga/PurchaseToPaySaga` | `SupplierService`, `SupplierProductPriceService`, `PurchaseRequisitionService`, `PurchaseOrderService`, `saga/PurchaseToPaySagaManager` | `SupplierController`, `SupplierProductPriceController`, `PurchaseRequisitionController`, `PurchaseOrderController`, `SagaApiController` |
| Finance | `CustomerInvoice`, `SupplierInvoice`, `Payment` (+ `PaymentAllocation`), `JournalEntry`, `CurrencyConverter` (interface) | `CustomerInvoiceService`, `SupplierInvoiceService` (incl. the three-way match), `PaymentService`, `JournalEntryService` (posting), `CustomerCancellationRefundService`, `ExchangeRateService` | `CustomerInvoiceController`, `SupplierInvoiceController`, `PaymentController`, `JournalEntryController`, `ExchangeRateController` |
| Views (*domain 11*) | — | one `*Projection` and one `*QueryPort` per view in `reporting` | one controller per view |

Shared values (`Money`, `Quantity`, `Sku`, `PaymentTerms`, `ProductType`, `ReplenishmentStrategy`,
`ValuationClass`, `ApprovedVendor`) live in `shared-kernel` or in the producer's events module when a
consumer must branch on them.

**Naming in code.** A field holding a port is named for its aggregate in full and in the plural
(`salesOrders`, `supplierInvoices`), never `repository` or `repo`. Static fields come before instance
fields, nested types before both. A method has one return at the end (early guards excepted) and every
branch is exhaustive; an unexpected value throws rather than being coerced. A fallback to a default
value is documented where it is made and where it is trusted, and logs when it fires.

---

## 15. Why the less obvious design choices are there

| Choice | Reason |
|---|---|
| Spring Data JDBC and `JdbcTemplate`, not JPA | Loading an aggregate loads only that aggregate, with no lazy walk into another context; the `version` check is written out. |
| Transactional outbox, not XA | Kafka cannot take part in XA. Even with a broker that can, a down broker would roll back every business write; the outbox lets the write succeed and publishes later. It also keeps working when the schemas become separate databases. |
| One topic per service, keyed by aggregate | Per-aggregate order is the one guarantee worth buying. A global order would cost the throughput the design exists for; per-event-type topics would split one aggregate's history. |
| JSON, no schema registry | The events modules are the schema; a breaking change fails a consumer's build. |
| A database trigger for the journal balance | It is the one place that neither a faulty service nor a manual fix can get past. |
| Totals kept by triggers or writers, never as aggregates | A total that can be written on its own can disagree with its facts (*domain 5.7*). |
| Advisory lock for the inbox check | It closes the window where two threads both see "not yet applied", with no schema change and contention only between real duplicates. |
| Status of a sales order folded from its lines; cancel reads the lines | The fold loses information, so a rule that reads it would be wrong for some line mixes (*domain 6.2*). |
| Two-phase cancel arbitrated by inventory | Cancel and ship race on the same lines; inventory owns those lines, so whichever claims them first wins, with no call between services. |
| Inventory fans out compensation legs | Only inventory knows which work order or purchase order serves which order-pegged line; the sales Saga cannot read that schema. |
| Saga milestones as linked one-span traces | A Saga outlives trace retention; its id, not a long trace, ties its history together. |
| Locale-free API | Translation lives where rendering does (the SPA). |

---

## 16. Left out, and open

**Left out:**
- Multi-currency conversion when posting (the converter exists; *domain 12*).
- A unique-claim inbox strategy for a database other than PostgreSQL (section 5.4).
- Operator controls for a stuck Saga or a parked dead-letter record.

**Open:**
1. **The layer rules are checked by search in review, not by a test** (section 3.4). Should they become
   an ArchUnit test in the build, with a case that plants a breach?
2. **An outbox row that never publishes** is retried for ever, with no limit and no terminal status, and
   a later row of the same aggregate can overtake it. Should the outbox get a cap and a dead state?
3. **A Saga that never advances** is retried for ever with back-off and has no terminal `failed` path from
   retries. Should retries be capped?
4. **Compensation has no timeout.** A Saga in `compensating` waits for ever for a leg that never answers
   (*domain 13*, question 1).
5. **The purchase-to-pay Saga does not carry the originating sales order**, so its milestones are not
   tagged with `northwood.sales_order_id`.
6. **`projection_checkpoint`** records a cursor that the live read side does not use (it uses Kafka
   offsets and the inbox). Remove it, or use it?
7. **The domain services of *domain 9.1* are application services in the code** (*domain 13*,
   question 4).
