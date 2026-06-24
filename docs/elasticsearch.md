# Elasticsearch Showcase for Northwood ERP — a CQRS Read-Model Extension

> A **design/plan doc**. The framing is deliberate: Elasticsearch is introduced as **another projection on the
> CQRS read side**, fed by the same domain-event log that already powers `reporting-service`'s Postgres
> projections — just materialized into a store optimized for search instead of relational queries. The write
> side does not change. Not yet implemented.
>
> **Scope: A + B + C** — three Elasticsearch read models off the one event log, built with the same projector
> mechanism and increasing in reach:
> 1. **Product catalog search** (full-text/fuzzy/autocomplete/facets) — ✅ **implemented** (`search-service`)
> 2. **Global cross-service search** (one box over every bounded context) — pending
> 3. **Audit/event analytics** (aggregations + Kibana, retiring the in-memory audit fan-out) — pending
>
> **Status:** the `search-service` module (recommended option below) is built, with read model A live. One
> implementation note vs this plan: the projectors have **no Postgres inbox** — search-service owns no database,
> so idempotency comes from **upsert-by-document-id** (document id = aggregate id) rather than the
> `inbox_message` table. They implement `InboxEnvelopeHandler` directly (see *Where the projector lives*).

---

## Context — why this is a natural CQRS extension

Northwood is already CQRS + event-driven:

```
aggregate (write side)  →  outbox (same tx)  →  Kafka *.events  →  inbox handlers  →  projection (read side)
```

`reporting-service` is the canonical example: ~20 inbox handlers consume events and maintain Postgres read
models (`SalesOrder360Projection`, `AvailableToPromiseProjection`, `FinancialDashboardProjection`, …), served
through `*QueryPort` read interfaces. **Adding Elasticsearch is just adding one more projection target** —
the same events, a new materialized view.

Two gaps make search the obvious thing to materialize:

1. **There is no real search today.** `product-service` is plain CRUD + list (`ProductController` →
   `JdbcProductRepository`); products carry `sku`, `name`, `description`, `productType` but nothing in the
   codebase does full-text, fuzzy, autocomplete, or faceting.
2. **The audit timeline doesn't scale.** Every service exposes `/api/audit` (shared `AuditController` →
   `JdbcAuditQueryAdapter`); the BFF's `AuditAggregatorController` **fans out to all 7 services and merges
   in-memory**, sorted by timestamp — no full-text, no aggregations. (See its own Javadoc + `docs/messaging.md`.)

Because the read model is derived from an immutable event log, an ES index is **disposable and rebuildable**:
drop it and replay Kafka (the same property `docs/messaging.md` notes for rebuilding Postgres
projections). That is the CQRS payoff this showcase leans on.

---

## The CQRS shape of the showcase

| CQRS concern | How it shows up here |
|---|---|
| **Command side** | Unchanged. Aggregates still emit events via the outbox. |
| **New read model** | One (or more) Elasticsearch indices. |
| **Projector** | A new event consumer that upserts ES documents — built like reporting's inbox handlers. |
| **Query side** | A search API (`/api/search/...`) reading ES, exposing relevance/fuzzy/facets — things SQL read models can't do well. |
| **Eventual consistency** | The ES view lags the write by the event-propagation delay — same contract as every other Northwood projection. |
| **Idempotency** | Reuse the existing **inbox** dedup (`AbstractInboxHandler` / `inbox_message`) so replays/at-least-once delivery don't double-apply; ES upserts are naturally idempotent by document id too. |
| **Rebuild** | Re-create the index by replaying the topics from offset 0 (clear the projector's `inbox_message` rows first; see `docs/messaging.md`). |

### Where the projector lives

Two options:

- **(Recommended) a dedicated `search-service` module** — a new query-side service that owns the ES indices,
  has its own Kafka consumer group, and exposes `/api/search/**`. Keeps the Elasticsearch dependency isolated,
  and makes the "new read model from the same event log" story crisp (parallels how `reporting-service` is a
  standalone read side, and how `erp-graphql-bff` was added as its own module in `docs/graphql.md`).
- **Add ES projections to `reporting-service`** — less new scaffolding (it already owns projections + a consumer),
  but mixes a second datastore into a Postgres-shaped service.

The rest of this doc assumes a `search-service` module; the projector code is identical either way.

### The three read models at a glance

| # | Read model | ES index | Fed by | Showcases |
|---|---|---|---|---|
| **A** | Product catalog search | `products` | `product.events` | full-text, fuzzy, autocomplete, facets, relevance |
| **B** | Global cross-service search | `entities` (typed) | `product` + `sales` + `purchasing` + `finance` events | one search box across every bounded context |
| **C** | Audit / event analytics | `audit-events` | **all** `*.events` | aggregations + Kibana; retires the in-memory audit fan-out |

All three are the same pattern — consume events, upsert ES docs, query the index — so B and C reuse A's projector
scaffolding rather than introducing anything new.

---

## Read model A — Product Catalog Search

### Index mapping (`products`)

```jsonc
{
  "mappings": {
    "properties": {
      "productId":   { "type": "keyword" },
      "sku":         { "type": "keyword",
                       "fields": { "text": { "type": "text" } } },   // exact + tokenized
      "name":        { "type": "text",
                       "fields": { "suggest": { "type": "completion" },   // autocomplete
                                   "keyword": { "type": "keyword" } } },  // faceting/sort
      "description": { "type": "text" },
      "productType": { "type": "keyword" },   // facet
      "status":      { "type": "keyword" },   // facet
      "salesPrice":  { "type": "scaled_float", "scaling_factor": 100 },  // range facet
      "manufactured":{ "type": "boolean" },
      "purchased":   { "type": "boolean" }
    }
  }
}
```

### Projector (new consumer, reporting-style)

Consume the product event stream and upsert by `productId` (document id = aggregate id ⇒ idempotent):

| Event | Action on `products` index |
|---|---|
| `ProductCreated` | index new doc |
| `SalesPriceChanged` / `StandardCostChanged` | partial update price fields |
| `MakeVsBuyChanged`, `ReplenishmentStrategyChanged`, … | partial update |
| `ProductDiscontinued` | set `status=discontinued` (or delete) |

(The exact event names are in `product-events`; the reporting `inbox/atp/*` handlers already consume several of
these, so the set is known-good.)

### Query API (the part SQL can't do well)

`GET /api/search/products?q=...&type=...&status=...&minPrice=...&maxPrice=...`

- **Full-text + fuzzy:** `multi_match` over `name`, `sku.text`, `description` with `fuzziness: AUTO` (typo
  tolerance — "labtop" finds "Laptop").
- **Autocomplete:** `GET /api/search/products/suggest?q=lap` via the `name.suggest` completion field.
- **Facets:** `terms` aggregations on `productType`, `status`; range agg on `salesPrice` — returns counts per
  bucket for filter UIs.
- **Relevance ranking:** boost `name`/`sku` over `description`; results scored, not just filtered.

---

## Read model B — Global cross-service search (same mechanism, more entities)

The product projector is a template. Point equivalent projectors at the other event streams and you get a
**unified search box** across the ERP — ES becomes the **search-side aggregator**, the read-optimized successor
to `AuditAggregatorController`'s in-memory fan-out:

| Source events | Searchable entity | Key fields |
|---|---|---|
| `product.events`   | Product        | sku, name, description |
| `sales.events`     | Customer, SalesOrder | customerCode, name, email; orderNumber, status |
| `purchasing.events`| Supplier, PurchaseOrder | supplier name/code; PO number |
| `finance.events`   | Customer/Supplier Invoice | invoice number, status |

Design choice: **one `entities` index with a `type` discriminator** (simplest cross-entity ranking, one query) vs
**per-entity indices queried with a multi-index search** (cleaner mappings). For a demo, a single index with a
`type` keyword field is the least friction. Result: `GET /api/search?q=acme` returns customers, orders, invoices
and products in one relevance-ranked list — one box, every bounded context.

---

## Read model C — Audit / event analytics (aggregations + Kibana)

Project **every** `*.events` message into an `audit-events` index: full-text + time-range search,
**aggregations** (events per service / type / day, error rates), and **Kibana** dashboards. This directly
retires the in-memory merge in `AuditAggregatorController` and showcases ES's analytics side rather than its
search side. Same CQRS shape — just the broadest read model off the same log (subscribes to all topics, not
just the entity-bearing ones), and the projector is the simplest of the three: no entity merge logic, just
append each decoded `EventEnvelope` as a document.

---

## Dependencies & config

**`search-service` (new module, parent `northwood-erp`, Spring Boot 4.0.5 / Java 21):**
- `org.springframework.boot:spring-boot-starter-data-elasticsearch` (Spring Data Elasticsearch over the new
  Elasticsearch Java API client)
- `org.springframework.boot:spring-boot-starter-web`
- `org.springframework.kafka:spring-kafka` (event consumer)
- `com.northwood:*-events` + `shared` (event types + inbox port, like reporting)
- `spring-boot-starter-test` + Testcontainers `elasticsearch` (IT)

```yaml
# application.yml
server: { port: 8088 }   # pick a free port (note: erp-graphql-bff also proposes 8088 — assign distinct ports)
spring:
  elasticsearch:
    uris: ${ELASTICSEARCH_URIS:http://localhost:9200}
  kafka:
    consumer:
      group-id: search-service           # distinct group → own offsets
      auto-offset-reset: earliest        # so a fresh index can replay history
northwood:
  events:
    # All topics: A/B (entity search) consume product/sales/purchasing/finance; C (audit analytics)
    # consumes every topic. Subscribe to all and let each projector filter the event types it cares about.
    subscribe-topics: product.events,sales.events,inventory.events,manufacturing.events,purchasing.events,finance.events
```

**Infra:** add `elasticsearch:8.x` (single-node, security off for dev) to the dev `docker-compose.yml`, plus
`kibana` (for read model C's dashboards). Add a healthcheck.

**BFF routing:** add a route in `erp-web-ui-bff`'s `RouteTable` — `/api/search` → `search-service` — so the SPA
reaches it through the existing OIDC/proxy path (same pattern as every other `/api/**` route).

---

## Touch points (files)

- **New module `search-service/`**: `pom.xml` (+ add to root `northwood-erp` `<modules>`), `application.yml`,
  index mappings (`@Document`-annotated docs or a JSON template), Kafka projector handlers (reporting inbox
  style), a `SearchController` exposing `/api/search/**`, a `SpringDataElasticsearchRepository` or
  `ElasticsearchOperations` query layer.
- **`erp-web-ui-bff/.../RouteTable.java`**: one new `Route("/api/search", "search", null)` + target URL in
  `application.yml` (`northwood.bff.targets.search`).
- **`docker-compose.yml`**: add `elasticsearch` + `kibana` (kibana powers read model C's dashboards).

---

## Verification (end-to-end)

1. `docker compose up elasticsearch` (+ existing infra); start `search-service`.
2. **Replay/rebuild:** with `auto-offset-reset: earliest` and an empty index, confirm the projector backfills
   the `products` index from historical `product.events` (`GET localhost:9200/products/_count`).
3. **Fuzzy search:** `GET /api/search/products?q=labtop` returns the "Laptop" SKU (typo-tolerant).
4. **Autocomplete:** `GET /api/search/products/suggest?q=lap` returns name completions.
5. **Facets:** a search response includes `productType`/`status` bucket counts and a price range agg.
6. **Live projection:** change a product's sales price via `product-service`; within the propagation delay the
   ES doc reflects it (eventual consistency — the CQRS contract).
7. **Global search (B):** `GET /api/search?q=acme` returns matching customers, orders, invoices and products
   in one relevance-ranked list (one box, every bounded context).
8. **Rebuild demo:** delete an index, clear the projector's `inbox_message` rows, restart → index rebuilds
   from the log (the disposable-read-model payoff).
9. **Analytics (C):** open Kibana, show an events-per-service/day aggregation over the `audit-events` index.

**Tests:** an `@SpringBootTest` IT with Testcontainers Elasticsearch asserting (a) a `ProductCreated` event
produces a searchable doc, (b) a fuzzy query matches, and (c) re-delivering the same event (inbox replay) leaves
exactly one doc (idempotent projection).

---

## Out of scope / deliberately avoided

- **Elasticsearch as a system of record.** It stays a derived read model; Postgres + the event log remain the
  source of truth (drop-and-rebuild is a feature, not data loss).
- **Replacing the Postgres reporting projections.** ES complements them (search/aggregations); the relational
  read models stay for transactional/joined queries.
- ES security/auth hardening, multi-node cluster, ILM/retention — production concerns, not demo.
- Reusing ES for the GraphQL/Redis showcases — orthogonal; see `docs/graphql.md`, `docs/redis.md`.
