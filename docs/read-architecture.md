# Northwood Read Architecture — GraphQL + Elasticsearch + Redis on One Event Log

> A **design/plan doc** that ties together the three companion proposals — [`graphql.md`](./graphql.md),
> [`elasticsearch.md`](./elasticsearch.md), [`redis.md`](./redis.md) — into a single coherent read-side
> architecture. It does not introduce new write-side concepts: the command side (aggregates → outbox → Kafka)
> is unchanged. Not yet implemented.

---

## The core idea

The three technologies are **not independent add-ons**. They are layers of one read architecture, and they
cohere because Northwood is already **CQRS + event-driven**: every state change is published once to the Kafka
`*.events` log, and each tool is a different *reaction* to that same log.

```
        WRITE SIDE (unchanged)                         READ SIDE (the three, layered)

  aggregate ─► outbox (same tx) ─► Kafka *.events ─┬─► Elasticsearch projections   FIND   (fuzzy / facets / analytics)
                                                   ├─► Redis cache eviction        ACCEL  (hot reads / autocomplete / session)
                                                   └─► GraphQL subscriptions       PUSH   (live updates)

  Browser ─► GraphQL BFF ─►  ES (search)  +  owning services (authoritative state)  +  Redis (session + cache)
```

**One write → three read-side reactions to the same event.** That single sentence is the whole architecture.

---

## Division of labor

Each tool does the job it is best at; none overlaps the others' core competency.

| Concern | Tool | Why it, not the others |
|---|---|---|
| Find by text / fuzzy / relevance / facets / aggregations | **Elasticsearch** | SQL read models and REST can't do typo-tolerance, ranking, or fast aggregations |
| Compose a request across services; client-chosen fields; one round trip | **GraphQL BFF** | aggregation + field selection + DataLoader batching over the 7 services |
| Authoritative current state of a single entity | **owning service (via GraphQL)** | the service is the source of truth; ES/Redis are derived |
| Hot/repeated reads, autocomplete, session/token | **Redis** | sub-ms cache + distributed session store |
| Live updates | **GraphQL subscription** (over the Kafka feed) | typed push on the same events that feed ES/Redis |

Mantra: **ES finds, the services own the truth, GraphQL composes, Redis accelerates, Kafka keeps all three in sync.**

---

## Fan-out vs. read model — choosing the backend for a known-entity query

A common question: for a composed query like `OrderCockpit` (fetch an order, traverse to its products / stock /
ATP / customer), is **GraphQL hitting multiple services live** better than reading **Elasticsearch**? For that
shape of query — *yes, decisively* — and ES would be the wrong tool. But the real tradeoff is subtler than
"GraphQL vs ES," so it's worth stating precisely.

### First, the framing

GraphQL is an *interface*; ES, the Postgres projections, and the services are *backends* it can resolve from.
So the question isn't "GraphQL or ES" — it's **which backend should answer a given field**. The deciding factor
is the **access pattern**:

- **Fetch a known graph** (you have an id, you traverse relationships, you need current values) → resolve from
  the **owning services** (live), composed by GraphQL.
- **Discover across many** (text, fuzzy, filters, ranking, aggregation) → resolve from **Elasticsearch**.

### Why ES loses for a known-id traversal

1. **You already have the id.** ES's strengths (fuzzy/relevance/facets/aggregations) are all about *finding*
   things you can't address directly — none apply to "fetch order SO-1234." It's a search engine doing a
   primary-key lookup.
2. **Freshness (decisive).** `stock.available`, `atp`, balances are exactly the volatile fields that must not be
   stale. ES is an **eventually-consistent projection**; the owning services are **authoritative**. Deciding
   "can I promise this stock?" off a lagging index is a correctness bug, not a cosmetic one.
3. **Read-only & derived.** Anything write-adjacent (the `placeOrder` mutation, read-after-write) goes to the
   services regardless.

### The real tension: live fan-out vs. a pre-joined read model

ES isn't actually the alternative to fan-out — a **pre-joined read model** is, and Northwood already has one for
this case: the `SalesOrder360` projection in `reporting-service`. Three ways to serve "show this order":

| Option | Freshness | Latency / resilience | Use for |
|---|---|---|---|
| **GraphQL fan-out** (live, multiple services) | ✅ authoritative | slower (= slowest upstream); a downstream outage nulls that field | freshness-critical fields (stock, ATP, balance) |
| **Read the `SalesOrder360` Postgres projection** | ⚠️ eventually consistent | fast, one store, resilient | stable summary/overview fields that tolerate small lag |
| **Read Elasticsearch** | ⚠️ eventually consistent | fast | only when you *also* need search / rank / aggregate |

ES and the Postgres projection are the **same category** (eventually-consistent read models); ES only earns its
place when search/ranking/aggregation is *also* required. For a plain id lookup it adds nothing the cheaper 360
projection doesn't.

### The cost of fan-out (the counterweight)

Live fan-out buys freshness but isn't free:
- **Latency** = the slowest upstream call (DataLoader batching + parallelism mitigates, not eliminates).
- **Availability** = product of every service touched — if inventory is down, that field fails (degrade to null,
  the way `AuditAggregatorController` does, rather than blanking the whole response).
- **No reuse** — you re-fetch each time; a projection is read once, cheaply, repeatedly.

So pay it only for fields that *must* be fresh.

### The pragmatic rule — blend per-field

GraphQL's real advantage is that one query can mix backends: serve the **stable summary** from the `SalesOrder360`
projection (fast, resilient) and resolve **only the volatile fields** (stock/ATP) **live** from the services. One
query, mixed sources, invisible to the client.

### When the answer flips to Elasticsearch

The moment the access pattern changes from *traverse one* to *discover across many* —
e.g. *"find all **overdue** orders for customers matching **'acme'**, sorted by amount, faceted by status"* — ES
wins (GraphQL fan-out has no full-text/relevance and would fan out across everything). Then it's both, in
sequence: **ES finds the matching orders → GraphQL hydrates the fresh fields** (the flow below).

---

## The combined request flow (uses all three at once)

A global search box — the canonical request that exercises the whole stack:

```graphql
query GlobalSearch($q: String!) {
  search(q: $q) {                  # 1. GraphQL resolver delegates to Elasticsearch
    ... on Product {
      product {                    # 3. hydrate authoritative fields from owning services (DataLoader)
        name salesPrice
        stock { available }        #    inventory  (Redis-cached)
        atp   { availableQuantity stockStatus }   # reporting (Redis-cached)
      }
    }
    ... on SalesOrder { order { orderNumber status totalAmount customer { name } } }
  }
}
```

1. **GraphQL** receives one typed query; auth rides a **Redis**-backed session (so the BFF can scale/restart).
2. **Elasticsearch** does the matching — fuzzy, ranked, across every bounded context — and returns ids/summaries.
   Autocomplete for the same box is served from a **Redis**-cached prefix→suggestions entry.
3. **GraphQL DataLoaders** hydrate the hits with *fresh* authoritative fields from the owning services, batched
   (1 upstream call per type), with hot reads (ATP/dashboard/catalog) served from **Redis** cache-aside.
4. Result: search ranking may be slightly stale (ES is a projection), but the **numbers shown are fresh**
   (hydrated from source). Live changes arrive via a **GraphQL subscription** on the same events.

No single tool can serve that query; each does one layer.

---

## The shared backbone — one event, three reactions

The elegant part: ES projection, Redis invalidation, and GraphQL push are all wired into the **same inbox/event
handlers** that already exist. You extend the existing consumers rather than inventing a new sync mechanism.

```
onEvent(e):                       # e.g. SalesPriceChanged(productId)
  elasticsearch.upsert(e)         # update the ES read model        (elasticsearch.md)
  redis.evict(keyFor(e))          # invalidate the matching cache    (redis.md)
  graphqlSink.tryEmitNext(e)      # push to live subscribers         (graphql.md)
```

This is why the three reinforce each other: a **single invalidation/refresh story** keeps all read paths
consistent, because they share the trigger. Get the key-mapping right once (event → ES doc id, event → Redis
cache key) and the whole read side stays coherent.

---

## Consistency model (the part to get right)

Layering an eventually-consistent index (ES) under a cache (Redis) under an aggregator (GraphQL) creates up to
three staleness windows. Keep the contract explicit and they stay small:

- **Search / lists / analytics** → read from **ES**; accept eventual consistency (it's a projection).
- **Authoritative state of one entity** → **GraphQL resolves from the owning service** (optionally Redis-cached,
  short TTL). This is the escape hatch: *find* stale-tolerant, *show* fresh.
- **Session / identity** → **Redis** (authoritative for the session lifetime).
- **TTL backstop:** tie every Redis TTL (and any ES refresh expectation) to the event-propagation delay, so a
  missed eviction self-heals quickly.
- **Rebuildability:** ES indices and Redis caches are both **disposable** — drop and replay the Kafka log
  (see `docs/messaging.md`). Postgres + the event log remain the source of truth.

The "find in ES, hydrate from the service" pattern is what makes the staleness acceptable: ranking can lag, the
displayed values cannot.

---

## Component map

| Component | Module | Role | Talks to |
|---|---|---|---|
| GraphQL BFF | `erp-graphql-bff` (new) | edge query/composition + subscriptions | services (REST), ES (search), Redis (session/cache) |
| Search read models | `search-service` (new) | ES projections + search API | Kafka (consume), Elasticsearch |
| Reporting read models | `reporting-service` | Postgres projections + query API | Kafka (consume), Postgres, Redis (cache-aside) |
| Session store | Redis | distributed BFF sessions | both BFFs |
| Cache | Redis | cache-aside + autocomplete | reporting, search, GraphQL resolvers |
| Event backbone | Kafka | the shared log | everything on the read side |

**Port hygiene:** `graphql.md` and `elasticsearch.md` both tentatively suggested port 8088 — assign distinct
ports when both modules exist (e.g. GraphQL BFF 8088, search-service 8093). New BFF routes (`/api/search`)
go in `erp-web-ui-bff`'s `RouteTable` as usual.

---

## Suggested adoption order

Each step is independently valuable and de-risks the next:

1. **Redis sessions** ✅ *shipped* — smallest change, unblocks scaling/restart of the BFFs (incl. the GraphQL
   one). Prereq for running GraphQL in anything but single-instance. (`erp-web-ui-bff` + `erp-graphql-bff`.)
2. **GraphQL BFF (queries + DataLoader + mutation + subscription)** ✅ *shipped* — the composition layer over
   the existing REST services. New `erp-graphql-bff` module (port 8088); see `docs/graphql.md`.
3. **Redis cache-aside on reporting** ✅ *shipped* — cache-aside on the three hot read models (ATP /
   financial-dashboard / sales-order-360) with event-driven, after-commit eviction at the projection-write
   seam (the wiring ES will reuse). `reporting-service`; see `docs/redis.md` Part 2.
4. **Elasticsearch search-service (A→B→C)** — 🟡 *in progress*: the `search-service` module (port 8093) is
   built with **read model A** (product catalog: full-text/fuzzy/facets/autocomplete) live and the BFF
   `/api/search` route wired; **B** (global cross-service search) and **C** (audit/event analytics) pending,
   then add the `search` field to GraphQL. See `docs/elasticsearch.md`.
5. **GraphQL subscriptions + cross-layer fan-out** — the final `event → ES + Redis + GraphQL` unification.

---

## Anti-patterns to avoid

- **Double-caching without an owner.** Don't cache the same datum in both Redis and ES with no clear
  invalidation owner — pick one read path per query type (table above).
- **ES as a system of record.** It's a derived read model; never the write target.
- **Forcing all three into every query.** A simple id lookup goes straight to the service via GraphQL — no ES,
  no Redis needed. Reach for a layer only when its strength applies.
- **Caching authoritative entity reads with a long TTL.** Freshness-critical fields (stock, balances) should be
  short-TTL or read-through, not long-lived cache entries.
- **Inventing a second sync path.** All read-side updates flow from the Kafka log via the existing inbox
  handlers — don't add direct service-to-ES or service-to-Redis writes that bypass the event log.

---

## See also

- [`graphql.md`](./graphql.md) — the GraphQL BFF (queries, DataLoader, mutation, Kafka subscription)
- [`elasticsearch.md`](./elasticsearch.md) — ES read models A+B+C (catalog / global / audit analytics)
- [`redis.md`](./redis.md) — distributed sessions + cache-aside with event-driven eviction
- [`messaging.md`](./messaging.md) — the event backbone, inbox/outbox, projection rebuild/replay
