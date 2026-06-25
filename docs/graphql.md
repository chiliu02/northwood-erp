# Spring GraphQL Demo for Northwood ERP

> A **design/plan doc** for a GraphQL showcase, grounded in the real REST DTOs the Northwood services
> already expose, so the schema below maps 1:1 to existing fields. **Implemented** as the `erp-graphql-bff`
> module (read-architecture step 2) — see *Implementation notes* at the foot for the deltas between this plan
> and what shipped.

---

## Context — why this demo

Northwood is a 7-service ERP (`product`, `sales`, `inventory`, `manufacturing`, `purchasing`,
`finance`, `reporting`) fronted by `erp-web-ui-bff` — a JDK-HttpClient **reverse proxy** on port 8089 that
forwards the SPA's `/api/**` calls to the owning service, attaching a Keycloak Bearer token sourced from the
OIDC session (`ProxyController` + `BffAccessTokenAccessor`).

Today, rendering one screen (e.g. a sales-order detail with product, stock and customer info) forces the SPA
to fire **N REST calls across 3–4 services** and stitch the result client-side. That is the canonical
**GraphQL-as-BFF** use case: one typed query, server-side stitching, exactly the fields the client asked for.

The existing `AuditAggregatorController` (fan-out to all 7 services, merge, return one timeline) already proves
the server-side aggregation pattern in this codebase — the GraphQL BFF generalizes it.

**Decisions:**
- A **new `erp-graphql-bff` module** (port **8088**), parallel to `erp-web-ui-bff`. Keeps the existing proxy BFF
  "light" (its stated design intent: web + kafka only); the GraphQL endpoint stands alone as a showcase.
- Full **queries + DataLoader + mutation + Kafka subscription** trifecta.

---

## What it showcases (the highlight query)

One request resolves across **four services**:

```graphql
query OrderCockpit($id: ID!) {
  salesOrder(id: $id) {          # sales-service   GET /api/sales-orders/{id}
    orderNumber
    status
    totalAmount
    customer { name email defaultPaymentTerms }   # sales-service   (by customerId)
    lines {
      productSku
      orderedQuantity
      unitPrice
      product {                  # product-service GET /api/products  (batched)
        name
        salesPrice
        stock  { onHand reserved available }      # inventory-svc  (batched)
        atp    { availableQuantity earliestAvailableDate stockStatus }  # reporting (batched)
      }
    }
  }
}
```

REST equivalent: `GET sales-orders/{id}` + 1 `customers/{id}` + N×(`products/{id}` + `stock-items/by-product/{pid}` + `atp/{pid}`).
GraphQL: **one POST**, and with DataLoader batching the upstream traffic collapses to **1 call per type** (see below).

---

## Schema (`src/main/resources/graphql/schema.graphqls`)

Field names mirror the real view records exactly (verified in the services).

```graphql
scalar BigDecimal
scalar Date          # LocalDate
scalar DateTime      # Instant

type Query {
  salesOrders: [SalesOrderSummary!]!     # reporting GET /api/sales-orders  (SalesOrder360View)
  salesOrder(id: ID!): SalesOrder        # sales     GET /api/sales-orders/{id} (SalesOrderView, has lines)
  products: [Product!]!                  # product   GET /api/products
  product(id: ID!): Product              # product   GET /api/products/{id}
  customer(id: ID!): Customer            # sales     GET /api/customers/{id}
  search(q: String, type: String): GlobalSearchResult!   # search GET /api/search (Elasticsearch read model B)
}

type SalesOrder {                        # source: sales SalesOrderView
  id: ID!
  orderNumber: String!
  orderDate: Date
  requestedDeliveryDate: Date
  status: String
  currencyCode: String
  subtotalAmount: BigDecimal
  taxAmount: BigDecimal
  totalAmount: BigDecimal
  paymentTerms: String
  customer: Customer                     # resolved from customerId  -> batch loader
  lines: [SalesOrderLine!]!              # embedded in SalesOrderView.lines
}

type SalesOrderLine {                    # source: sales SalesOrderLineView
  lineId: ID!
  lineNumber: Int
  productSku: String
  orderedQuantity: BigDecimal
  reservedQuantity: BigDecimal
  shippedQuantity: BigDecimal
  unitPrice: BigDecimal
  lineTotal: BigDecimal
  lineStatus: String
  product: Product                       # resolved from productId  -> batch loader (N+1 hotspot)
}

type Product {                           # source: product ProductView
  id: ID!
  sku: String!
  name: String
  description: String
  productType: String
  salesPrice: BigDecimal
  standardCost: BigDecimal
  status: String
  stock: StockItem                       # resolved from productId  -> inventory batch loader
  atp: AvailableToPromise                # resolved from productId  -> reporting batch loader
}

type StockItem {                         # source: inventory StockItemView
  productId: ID!
  onHand: BigDecimal
  reserved: BigDecimal
  available: BigDecimal
  reorderPoint: BigDecimal
}

type AvailableToPromise {                # source: reporting AvailableToPromiseView
  productId: ID!
  onHandQuantity: BigDecimal
  reservedForSales: BigDecimal
  availableQuantity: BigDecimal
  incomingFromProduction: BigDecimal
  incomingFromPurchase: BigDecimal
  earliestAvailableDate: Date
  stockStatus: String
}

type Customer {                          # source: sales CustomerView
  id: ID!
  customerCode: String
  name: String
  email: String
  status: String
  defaultPaymentTerms: String
  billingAddress: String
  shippingAddress: String
}

type SalesOrderSummary {                 # source: reporting SalesOrder360View (list, no lines)
  salesOrderHeaderId: ID!
  orderNumber: String!
  customerName: String
  orderStatus: String
  totalAmount: BigDecimal
  outstandingAmount: BigDecimal
  hasShortage: Boolean
}

type Mutation {
  placeOrder(input: PlaceOrderInput!): SalesOrder    # POST sales /api/sales-orders
}

input PlaceOrderInput {
  orderNumber: String!
  customerCode: String!                  # NB: command keys on customerCode, not customerId
  currencyCode: String!
  requestedDeliveryDate: Date
  paymentTerms: String
  lines: [PlaceOrderLineInput!]!
}
input PlaceOrderLineInput {
  productId: ID!
  productSku: String!
  productName: String!
  orderedQuantity: BigDecimal!
  unitPrice: BigDecimal
  taxRate: BigDecimal
}

type Subscription {
  events: DomainEvent!                   # live *.events Kafka feed
}
type DomainEvent {
  eventId: ID!
  eventType: String!
  sourceService: String!
  aggregateType: String
  aggregateId: ID
  occurredAt: DateTime
}
```

`BigDecimal` / `Date` / `DateTime` scalars come from `com.graphql-java:graphql-java-extended-scalars`,
registered via a `RuntimeWiringConfigurer` bean (`ScalarConfig`).

---

## The N+1 story — DataLoader batching (the teaching highlight)

The naive resolver for `SalesOrderLine.product` runs once **per line**: a 10-line order ⇒ 10 calls to
product-service, 10 to inventory, 10 to reporting. This is exactly the N+1 problem.

**Fix:** use Spring for GraphQL's `@BatchMapping` (auto-registers a `DataLoader`). Because each of
product / stock / atp exposes a **list** endpoint, one batch call serves all keys:

| Batch loader     | Upstream call (once per request)  | Indexed by   |
|------------------|-----------------------------------|--------------|
| products by id   | `GET product   /api/products`     | `productId`  |
| stock by product | `GET inventory /api/stock-items`  | `productId`  |
| atp by product   | `GET reporting /api/atp`          | `productId`  |
| customers by id  | `GET sales     /api/customers`    | `customerId` |

Result: a 10-line order resolves the whole graph in **4 upstream calls total** instead of ~31.
The logs (one line per upstream `GET`) make the before/after obvious — that is the demo's money slide.

```java
@BatchMapping(typeName = "SalesOrderLine")     // batches all lines' productIds
public Map<SalesOrderLineView, Product> product(List<SalesOrderLineView> lines, GraphQLContext ctx) {
    Map<UUID, Product> byId = upstream.listProducts(token(ctx));   // ONE call
    return lines.stream().collect(toMap(l -> l, l -> byId.get(l.productId())));
}
```

---

## Auth — propagating the Keycloak token to async resolvers

Copy the proven pattern from `erp-web-ui-bff`:
- `application.yml`: same `spring.security.oauth2.client` Keycloak registration (OIDC code flow).
- `GraphqlSecurityConfig` (copy of `BffSecurityConfig`): `oauth2Login`, permit `/graphiql/**`,
  `/actuator/health|prometheus`, everything else authenticated; CSRF disabled (JSON API).
- `BffAccessTokenAccessor` (copied): pulls the access token from the OIDC session.

**Gotcha:** batch loaders run on **other threads** (CompletableFuture), where `SecurityContextHolder` is empty.
So capture the token **once per request** on the request thread and stash it in the `GraphQLContext`:

```java
// TokenPropagationInterceptor implements WebGraphQlInterceptor
request.configureExecutionInput((ei, b) ->
    b.graphQLContext(Map.of("accessToken", tokens.currentAccessToken())).build());
```

Resolvers/loaders read it via `@ContextValue("accessToken")` or `ctx.get("accessToken")` and pass it to the
upstream HTTP client as `Authorization: Bearer …`.

---

## Subscription over Kafka

Reuse `EventsAggregatorController`'s decode logic. A `@KafkaListener` on the `*.events` topics pushes decoded
`DomainEvent`s into a Reactor `Sinks.Many` (multicast); the subscription returns `sink.asFlux()`:

```java
@SubscriptionMapping
public Flux<DomainEvent> events(@Argument String aggregateType,
                                @Argument String eventType,
                                @Argument String sourceService) {
    return sink.asFlux().filter(/* keep only events matching the non-null filters */);
}
```

- Active only under `@Profile("kafka")` + `application-kafka.yml` (copy from the existing BFF; use a **distinct**
  consumer `group-id`, e.g. `erp-graphql-bff`, so it has its own offsets). Under the default `dev` profile the
  subscription field exists but never emits — note this in the demo.
- Transport: enable WebSocket (`spring.graphql.websocket.path: /graphql`) so GraphiQL can run subscriptions;
  add `spring-boot-starter-websocket`.
- **Filter args (read-architecture step 5 / read-model B):** `events(aggregateType, eventType, sourceService)`
  narrows the multicast per-subscriber, so "watch only `Product` events" and "watch everything" run side by side
  off the one Kafka feed — the live-push reaction of the *one event → ES + Redis + GraphQL* backbone.

---

## Demo (GraphiQL) — read-architecture step 5

The UI for these is **GraphiQL**, the interactive IDE the BFF serves at
**http://localhost:8088/graphiql** (queries over HTTP, subscriptions over the WebSocket at `/graphql`). No SPA
is needed — GraphiQL *is* the demo surface. Run the stack with `SPRING_PROFILES_ACTIVE=kafka` (the subscription
needs it) and the seed data up.

### A — find → hydrate (one query, ES finds, the service shows fresh truth)

Type a fuzzy term in one query; the ES global search (read model B) finds the hits, and the **same query**
hydrates the *fresh* authoritative record per hit from the owning service — including live stock/ATP:

```graphql
query FindAndHydrate {
  search(q: "labtop") {                 # ES fuzzy match (typo-tolerant) — read model B
    totalHits
    entityTypeFacets { key count }      # per-type counts
    hits {
      entityType title code score
      product {                         # hydrated LIVE from product-service when entityType = product
        name salesPrice status
        stock { available }             #   inventory (DataLoader-batched)
        atp   { availableQuantity stockStatus }   # reporting (DataLoader-batched)
      }
      salesOrder { orderNumber status totalAmount }   # when entityType = sales_order (live from sales)
      customer   { name email status }                # when entityType = customer (live from sales)
    }
  }
}
```

What to point out: search ranking may lag (ES is a projection), but `salesPrice` / `stock.available` / `atp`
are **fresh** (hydrated from source); the BFF logs show one upstream call per type (DataLoader batching), not one
per hit.

### B — filterable live feed (watch one change ripple, over WebSocket)

Open a subscription in one GraphiQL tab, narrowed to just product events:

```graphql
subscription WatchProducts {
  events(aggregateType: "Product") {    # drop the arg to watch EVERY service's events
    eventType sourceService aggregateId occurredAt
  }
}
```

Then in another tab (or the REST UI) change a product's price. The event streams in live — and *the same event*
also re-indexed the ES `products` doc and evicted reporting's Redis cache (the *one event → three reactions*
backbone, *As built* in `read-architecture.md`). Re-run query **A**'s search and the hydrated `salesPrice` shows
the new value.

---

## Module layout

```
erp-graphql-bff/
  pom.xml                                  # add <module> to root northwood-erp pom <modules>
  src/main/resources/
    graphql/schema.graphqls
    application.yml                        # port 8088, OIDC client, targets, graphiql + ws enabled
    application-kafka.yml                  # subscription wiring (copy, distinct group-id)
  src/main/java/com/northwood/graphqlbff/
    ErpGraphqlApplication.java
    config/GraphqlSecurityConfig.java       # copy of BffSecurityConfig
    config/ScalarConfig.java                # extended-scalars RuntimeWiringConfigurer
    config/TokenPropagationInterceptor.java # WebGraphQlInterceptor -> GraphQLContext
    upstream/ErpTargets.java                # copy of ErpBffTargets (@ConfigurationProperties)
    upstream/BffAccessTokenAccessor.java    # copied
    upstream/UpstreamClient.java            # JDK HttpClient wrapper, Bearer + Jackson(tools.jackson), async
    graphql/QueryController.java            # @QueryMapping salesOrders/salesOrder/products/product/customer/search
    graphql/ProductBatchController.java     # @BatchMapping Product.stock, Product.atp
    graphql/SalesOrderBatchController.java  # @BatchMapping SalesOrderLine.product, SalesOrder.customer
    graphql/SearchHitController.java        # @BatchMapping EntityHit.product/customer, @SchemaMapping .salesOrder (read-arch step 5: hydrate ES hits)
    graphql/MutationController.java         # @MutationMapping placeOrder
    graphql/EventSubscriptionController.java # @SubscriptionMapping events(filters)
    dto/                                    # mirrored view records (decoupled, like AuditRow):
                                            #   SalesOrderView, SalesOrderLineView, CustomerView,
                                            #   ProductView, StockItemView, AvailableToPromiseView,
                                            #   SalesOrder360View, DomainEvent, PlaceOrder* inputs
```

DTOs are **mirrored locally** (records), matching the existing BFF's `AuditRow` precedent — the BFF deliberately
avoids a Maven dep on the service/`shared` modules to stay light.

---

## Dependencies (`erp-graphql-bff/pom.xml`)

Parent `northwood-erp`; inherits the Spring Boot 4.0.5 BOM (Jackson 3 = `tools.jackson`).

- `spring-boot-starter-graphql`
- `spring-boot-starter-web`
- `spring-boot-starter-oauth2-client`        (OIDC session — copy from BFF)
- `spring-boot-starter-websocket`            (subscription transport)
- `spring-kafka`                             (subscription source)
- `com.graphql-java:graphql-java-extended-scalars`   (BigDecimal/Date scalars)
- `spring-boot-starter-actuator` + `micrometer-registry-prometheus` (parity with BFF; optional)
- `spring-boot-starter-test` + `spring-graphql-test` (`GraphQlTester`) — test scope

> Boot-4 note: GraphQL data fetching here is **blocking** (JDK HttpClient `sendAsync` → `CompletableFuture`),
> so MVC is fine — no WebFlux needed for queries. `reactor-core` (for the subscription `Flux`) is already
> transitively present via `spring-boot-starter-graphql`.

---

## Config highlights (`application.yml`)

```yaml
server: { port: 8088 }
spring:
  graphql:
    graphiql: { enabled: true }       # interactive IDE at /graphiql
    websocket: { path: /graphql }     # subscriptions
  security:
    oauth2: { client: { ... } }       # same Keycloak block as erp-web-ui-bff
northwood:
  bff:
    targets:                          # same as BFF
      product:   http://localhost:8081
      sales:     http://localhost:8082
      inventory: http://localhost:8083
      finance:   http://localhost:8086
      reporting: http://localhost:8087
```

---

## Verification (end-to-end)

1. **Build first** (multi-module gotcha — `spring-boot:run` reads `~/.m2` jars, not `target/classes`):
   `mvn -pl erp-graphql-bff -am install`
2. Start infra + the services (existing project workflow: docker compose for Postgres/Keycloak/Kafka, then the
   7 services; `docker-compose.seed.yml` + `.claude/commands/run-svc.md` / `smoke.md` document the run recipe).
3. `mvn -pl erp-graphql-bff spring-boot:run`  → open **http://localhost:8088/graphiql**, log in via Keycloak.
4. **Query:** run the `OrderCockpit` query above with a real order id; confirm in the app logs that
   product/stock/atp/customer each hit upstream **once** (batching proof). Re-run with fewer selected fields and
   watch the upstream calls for unselected types disappear (field-driven fetching).
5. **Mutation:** run `placeOrder` with a valid `customerCode` + product line (caller needs the **sales-clerk**
   role on the Keycloak token); confirm a `SalesOrderView` comes back and the order is queryable.
6. **Subscription:** run the module under the `kafka` profile (`SPRING_PROFILES_ACTIVE=dev,kafka`), open a
   `subscription { events { eventType sourceService } }` tab in GraphiQL, then place an order in another tab and
   watch events stream in live.

**Tests:** a `GraphQlTester` web-slice test with a stubbed `UpstreamClient` that **counts upstream calls** —
asserting a multi-line order triggers exactly one `listProducts()` — is the unit-level proof of the batching
claim, and fits the module (no DB/aggregates, so the project's "every `Jdbc*` has an `*IT`" rule doesn't apply).

---

## Out of scope (possible follow-ups)

- Query depth/complexity limits (mentioned as a GraphQL risk; not needed for a demo).
- A true batch-by-ids upstream endpoint (current list-fetch batching is fine at demo scale).
- Persisted queries / HTTP caching.

---

## Implementation notes (deltas from this plan)

What shipped follows the plan above; the differences worth recording:

- **DTO fields are the *full* upstream records, verbatim.** Mirroring every component (not just the schema
  subset) means Jackson deserialises upstream JSON exactly with no unknown-property handling. The schema
  exposes a subset; e.g. `SalesOrderLine` gained `backorderedQuantity` (a real `SalesOrderLineView` field the
  original plan omitted).
- **`id` rename via `@JsonProperty`.** Upstream keys are `productId` / `customerId`; the schema exposes them as
  `id`. The mirrored `ProductView` / `CustomerView` name the component `id` with
  `@JsonProperty("productId")` / `@JsonProperty("customerId")`, so Spring GraphQL's default property mapping
  resolves `id` with zero extra resolver code. (jackson-annotations stays at `com.fasterxml.jackson.annotation`
  even on Jackson 3.)
- **`ID` arguments bound as `String`, parsed to `UUID` in the resolver** (and `PlaceOrderLineInput.productId`
  kept as `String`) — sidesteps any String→UUID argument-binding conversion; the string serialises straight
  into the sales command's `UUID` field.
- **`DateTime` scalar ⇒ `OffsetDateTime`, not `Instant`.** The graphql-java `DateTime` scalar serialises
  `OffsetDateTime`/`ZonedDateTime` but not `Instant`, so `DomainEvent.occurredAt` is an `OffsetDateTime` and the
  Kafka decoder normalises the envelope's instant to UTC.
- **`graphql-java-extended-scalars` pinned to `24.0`** (newest published) against the graphql-java `25.0` the
  Boot 4.0.5 BOM ships — the `Coercing` scalar API is unchanged 24→25.
- **Security: everything authenticated, only actuator health/prometheus permitted.** Rather than permitting
  `/graphiql/**`, an unauthenticated navigation is redirected to Keycloak by `oauth2Login`; after login GraphiQL
  loads and its same-origin `/graphql` calls carry the session. `GraphqlSecurityConfig` also registers the
  session-backed `HttpSessionOAuth2AuthorizedClientRepository` (read-architecture step 1), so tokens ride in
  Redis. The Keycloak `northwood-bff` client gained the `:8088` redirect URIs.
- **Batching proof test deferred.** The end-to-end `GraphQlTester` "one upstream call per type" test needs Boot
  4's GraphQL test-slice (`@GraphQlTest` relocated out of `spring-boot-test-autoconfigure`); shipped instead is a
  deterministic `BatchLoadersTest` covering the association/miss→null behaviour the `@BatchMapping` loaders rely
  on. The batching is still observable live via the one-line-per-upstream-call logs (the *money slide*).
