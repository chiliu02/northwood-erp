# Conventions — naming, View/Command pattern, exception wrapping, silent fallbacks, single-return + exhaustive branches

Detail companion to `CLAUDE.md`. Read when adding an application service, a controller, an inbox handler, or anything that touches the api ↔ application ↔ domain seam.

## Naming conventions for ports, adapters, repositories, lookups, queries

This codebase blends DDD, Hexagonal, and CQRS vocabularies. Each suffix carries a specific architectural signal:

| Role | Interface | Concrete impl |
|---|---|---|
| Infrastructure machinery (inbox/outbox/saga) | `*Port` | `Jdbc*Adapter` |
| Domain aggregate read+write (DDD Repository pattern) | `*Repository` | `Jdbc*Repository` |
| CQRS read-side projection (whole rows, lists) | `*QueryPort` | `Jdbc*QueryPort` |
| Narrow operational value lookup (single method, e.g. `findUnitPrice`) | `*Lookup` | `Jdbc*Lookup` |
| Inbox-event-driven write to a read model | `*Projection` (in `application/inbox/`) | `Jdbc*Projection` (in `infrastructure/persistence/`) |

**`*Repository` rule (no `*Repository` without an aggregate).** Every `*Repository` interface in `<service>.domain/` must have a sibling aggregate root class in the same package that declares `public static final String AGGREGATE_TYPE` and is the integrity boundary for at least one invariant. Where the aggregate has post-creation behaviour, that behaviour is expressed as intent-named mutators on the root and the events they emit are drained from `pendingEvents` to the outbox by the repository at `save()` in the same transaction. **Event-less aggregates are permitted** when the aggregate is write-once (factory-only, no mutators, no events) and the invariant is enforced elsewhere — `finance.JournalEntry` is the exemplar: posted once with balanced lines, the DB trigger `enforce_journal_balance` carries the balance invariant, and reversal is itself a new posted entry rather than a mutation. Such cases still declare `AGGREGATE_TYPE` even though no outbox write references the constant — the aggregate root's identity remains the audit-log + reporting anchor. The suffix carries an architectural contract — readers seeing `*Repository` rightly expect "DDD aggregate read+write with the collection illusion," not "row-level write port with invariants enforced one layer up." A `*Repository` that doesn't satisfy the contract misleads every future reader of the codebase; for a showcase that is read as much as run, that is a code-review fail.

Two legitimate options when a candidate doesn't yet warrant aggregate modelling:

1. **Promote.** Introduce the aggregate root with `AGGREGATE_TYPE`, lift the invariants from the application service into intent-named mutators on the root, drain `pendingEvents` at `save()`. This is the right answer when the candidate has real domain invariants (state machine, cross-field guards, lifecycle) and the application-service-shaped enforcement is starting to feel like it's papering over a missing model.
2. **Pick a different suffix.** `*Writer` / `*Gateway` / `*Editor` are not in the codebase's documented vocabulary today; introducing one means adding a row to the vocabulary table above with a clear semantic. The cost of expanding the vocabulary is small and worth it only when the same shape repeats in multiple places.

Re-using `*Repository` for a non-aggregate port to "save a rename" is exactly the anti-pattern the suffix exists to prevent. The naming check is mechanical: `find **/domain/*Repository.java` should have a 1:1 correspondence with files in the same package declaring `AGGREGATE_TYPE`.

**No known exceptions remain** as of 2026-05-16. The 2026-05-15 audit surfaced five `*Repository`-without-an-aggregate offenders; all five were resolved across §2.16 / §2.17 / §2.18:

- §2.16 — `manufacturing.BomEditRepository` deleted; replaced by a real `Bom` aggregate root with `AGGREGATE_TYPE`, intent-named mutators (`addLine`, `removeLine`, `activate`), `pendingEvents` (emits `manufacturing.BomActivated` on activation), and a `BomRepository` that drains events at `save()`. State-machine invariants (status guards, line-number allocation, "at most one active per product" via the DB partial unique index `uq_bom_active_per_product`) now live on the aggregate; `BomEditService` is reduced to a thin orchestrator over the aggregate + cycle-detector + materials-cost rollup. `BomCycleDetector` is application-service-orchestrated (post-save cycle walk over the DB graph) rather than passed into the aggregate as a parameter — documented in `Bom`'s class Javadoc.
- §2.17 — `purchasing.SupplierProductPriceRepository` is now a real DDD Repository for the new `SupplierProductPrice` aggregate root. `SupplierProductPriceChanged.AGGREGATE_TYPE` constant retired from the event class.
- §2.17 — `product.ApprovedVendorRepository` deleted; the approved-vendor list folded into the `Product` aggregate as a child collection (mutated via `Product.setApprovedVendors`, dirty-flag-driven persistence by `JdbcProductRepository` to the denormalised `product.approved_vendor` table).
- §2.18 — `manufacturing.RoutingRepository` and `purchasing.SupplierRepository` had no mutators / events / `pendingEvents` — renamed to `RoutingQueryPort` / `SupplierQueryPort` (with the interfaces moved from `domain/` to `application/`) since they're CQRS read-side ports, not DDD repositories.

**`*Projection` rule (sharper than the others).** A `*Projection` lives in `application/inbox/` and is consumed *only* by `*Handler` classes in the same `inbox/` package. It exists solely to write inbox-event-derived facts onto a read-model table. If any non-handler caller ever needs to access the same table — a saga worker, a controller, a backfill batch — it goes through a separate class (`*Lookup` for value reads, `*QueryPort` for whole-row reads, `*Writer` for non-event-driven writes), not the projection. Implementations may use `JdbcTemplate` (split into the interface in `application/inbox/` + `Jdbc*Projection` in `infrastructure/persistence/`) or delegate through an aggregate `*Repository` (concrete class in `application/inbox/`, no infra-side file — see `inventory.application.inbox.StockItemProjection`). The package location encodes the architectural fact that projections are event-driven; `*Service` (without the `Projection` suffix) is reserved for command-side orchestration in `application/`.

**Projection vs aggregate.** Most projection tables (`sales.product_pricing`, `manufacturing.product_active_bom`, `manufacturing.product_replenishment`, `purchasing.product_approved_vendor`, `finance.product_accounting`, all 6 reporting views) are **caches of facts owned by another service** — the consumer reads them locally because the per-service `search_path` blocks cross-schema joins, but mutation authority lives upstream with the producer. These stay as projections; promoting them to a `*Repository`+aggregate would mislead readers about ownership ("is this where I change pricing?" — no, that's `Product.changePricing()` in product-service). The aggregate shape is for state the **consuming service authoritatively owns + mutates + holds invariants over**. Promote a projection to a real aggregate only when consumer-side invariants emerge that need a place to live (full criteria below in *Aggregate vs projection — deltas get aggregates…*). `inventory.stock_item` is currently aggregate-shaped but emits zero events — flagged for demotion as `dev-todo.md` §2.22 since it is structurally a snapshot projection of upstream product-master state, not an emitter of inventory-originated facts. The candidate to watch for *future* promotion is `finance.purchase_order_line_facts` — currently a projection, would become an aggregate if 3-way-match logic grows beyond the simple comparisons in `SupplierInvoiceService` (variance handling, partial-receipt-with-multiple-invoices, etc.).

**Consumer-side projection tables: one table per (schema, aggregate, shared-lifecycle attribute group).** When a consumer schema holds multiple facts about the same upstream aggregate, group them into one table named after the *schema's view of the aggregate*, not after individual columns. Each attribute column is 1:1 with the aggregate and shares its lifecycle (born on `*Created`, die on `*Discontinued` / equivalent terminal event). The standard wiring per consumer schema:

| Step | Handler | Operation |
|---|---|---|
| Birth | `*-product-created` (or equivalent for the source aggregate) | `INSERT (id) ... ON CONFLICT DO NOTHING` — stub row with all attribute columns NULL |
| Attribute change | one handler per `*Changed` event | plain `UPDATE` on the column it owns; the seed guarantees the row exists. A WARN-and-fallback `INSERT ON CONFLICT DO UPDATE` covers anomaly cases (seed missed, out-of-order delivery) without making upsert the normal path |
| Sunset | `*-product-discontinued` | plain `UPDATE` stamping `discontinued_at`, with the same fallback as attribute changes |

The smell that flags a missing consolidation: **projection tables named after a single column** (e.g. the historical `finance.product_standard_cost` / `finance.product_valuation_class`) instead of after the schema's view of the aggregate (e.g. the consolidated `finance.product_accounting`). The unit of grouping is *shared lifecycle*, not *cardinality* — `manufacturing.product_replenishment` and `manufacturing.product_active_bom` are intentionally separate because the active BOM changes over a Product's life independently of replenishment policy, so their lifecycles diverge.

Read side: the consolidated projection has two ports per the *Projection-vs-Lookup rule above. The write port (`*Projection` in `application/inbox/`) is consumed only by the per-event `*Handler` classes; non-handler readers (services, other handlers) go through a separate `*Lookup` (`application/`) — example: `finance.application.ProductAccountingLookup`'s `findStandardCost` / `findValuationClass`, called by `JournalEntryService` and `ShipmentPostedCogsHandler`. The JDBC layer has matching split classes (`JdbcProductAccountingProjection` for writes, `JdbcProductAccountingLookup` for reads).

Service-specific saga narrowings (e.g. `SalesOrderFulfilmentSagaPort extends SagaPort<...>`) keep the `*Port` suffix — they're still abstract ports over saga state. They live in the service's `application/saga/` (not `domain/saga/` — they reference the shared application-layer `SagaPort`, which is application-shape, so they belong on that side of the layering line). Per-service saga adapters are `Jdbc<Flow>SagaAdapter` next to their saga worker in `infrastructure/saga/`.

## Aggregate vs projection — deltas get aggregates, totals and snapshot projections get projection ports

The `*Repository` rule above answers *which suffix a port gets*. This section answers the prior question: **what makes a concept deserve an aggregate at all?**

**The rule, in one sentence.** Concepts that emit deltas (facts with identity, a lifecycle, and downstream consumers) get aggregates. Concepts that hold the *running sum* of those deltas, or that hold a *local cache* of facts owned upstream, get projection-shaped ports — they are not aggregates.

**The four categories.** Every persistence-touching concept in this codebase belongs to one of these:

| Category | Examples | Port suffix | Why |
|---|---|---|---|
| **Delta** — emits a fact with identity | `JournalEntry`, `Payment`, `GoodsReceipt`, `Shipment`, `StockReservation`, `WorkOrder`, `SalesOrder`, `PurchaseOrder`, `CustomerInvoice`, `SupplierInvoice`, `Customer`, `Product`, `Bom`, `SupplierProductPrice` | `*Repository` (aggregate root) | The fact has business identity and lifecycle; downstream consumers reason about it via its event. |
| **Total** — running sum over other aggregates' deltas | `inventory.stock_balance` (sum of `GoodsReceipt` / `Shipment` / `StockReservation` / `WorkOrderManufacturingCompleted`), gl-account balance (sum of `JournalEntry` lines), `customer_invoice_header.paid_amount` (trigger-maintained off `payment_allocation`) | `*Writer` + `*Lookup` (or DB trigger / `GENERATED ALWAYS AS`) | The total has no identity of its own; it is a derived view. Promoting it to an aggregate makes it possible for the total to diverge from the facts that produced it. |
| **Snapshot projection of upstream state** — local cache of a fact owned by another service | `sales.product_pricing`, `manufacturing.product_active_bom`, `manufacturing.product_replenishment`, `purchasing.product_approved_vendor`, `finance.product_accounting`, all 6 reporting views | `*Projection` (write) + `*Lookup` / `*QueryPort` (read) | The mutation authority lives upstream with the producer. The consumer reads locally because per-service `search_path` blocks cross-schema joins, but the consuming service holds no invariants over the data. |
| **Reference data** — seeded once, no commands | `warehouse`, `work_center`, `gl_account`, `tax_code`, `uom` | `*Lookup` or `*QueryPort` (read-only); no Java class needed in `domain/` | No commands ever mutate this through the domain. SQL seeds it; FKs reference it. If supplier/warehouse/etc. onboarding becomes a real user story with its own commands, the candidate is promoted to *Delta* — see `purchasing.Supplier` Javadoc for the documented future-promotion stance. |

**Promotion criteria** — a candidate crosses the line into "deserves an aggregate" when *any* of these become true:

- It emits its own domain event (an outbox row stamped with the candidate's own `aggregate_type`).
- It has commands that enforce in-memory invariants spanning more than one row inside one transaction.
- It has a state machine of its own (status transitions guarded by predicates over its own state).
- It has a lifecycle term in the ubiquitous language (`register`, `confirm`, `cancel`, `post`, `void`, etc.).

Until at least one of those is true, an aggregate skeleton is empty scaffolding — the `pendingEvents` list stays empty, the `AGGREGATE_TYPE` constant is never stamped on an outbox row, and the `*Repository` mis-applies a contract every reader expects to mean "DDD aggregate read+write." A `*Repository` that hides an inbox-driven projection is a worse mistake than the absent abstraction, because it costs every future reader the wrong assumption.

**Conceptual lineage** (oldest → most-specific):

```
Pacioli double-entry bookkeeping (1494)   ← the epistemic root
   ↓
Event sourcing (Greg Young, ~2006)        ← the CS restatement: state = fold(events)
   ↓
DDD aggregates + outbox-style publishing  ← Northwood's actual machinery
   ↓
"deltas get aggregates,                    ← the rule itself
 totals get projections"
```

The deepest root is **Pacioli (1494)**: *the journal is the system of record; the ledger is a report off it.* If the total can be edited independently of the facts, fraud/error becomes undetectable — the same reason the rule survives in software 500 years later. **Event sourcing (Young, ~2006)** restates the same epistemology in programming-pattern vocabulary: state is a `fold` over an append-only log. **CQRS** is *adjacent but not the root* — CQRS is read/write separation; it is silent on what should be a delta vs a total. You can CQRS a fully mutable aggregate; you can event-source without CQRS. **DDD (Evans, 2003)** gives the vocabulary (aggregate as transactional consistency boundary, repository); the deltas/totals rule sits one layer below DDD — an answer to "given I'm using aggregates, *which* concepts qualify?"

Northwood is **not event-sourced** — aggregates have a canonical row in the DB, not a fold over their own events. But for things that are naturally append-only (`JournalEntry`) or clearly running totals over upstream events (`stock_balance`, `customer_invoice_header.paid_amount`), the project applies the journal-vs-ledger discipline selectively. If a future conversation asks "is this event sourcing?" / "is this CQRS?" — the answer is "neither, exactly; it's DDD borrowing event-sourcing's epistemology selectively, and CQRS only on the read-projection side where it's ergonomically useful."

**Why this rule is load-bearing for the showcase.** Event classes are the navigation anchor for cross-service traceability (see `docs/architecture.md` → *Tracing data flow*). If every persistent concept were an aggregate, Find Usages on an event class would return noise — projections, totals, and aggregates indistinguishable. The rule keeps the aggregate roster equal to the set of fact-emitters, so the navigation guarantee holds: an event class names the concept that owns its lifecycle.

**Worked example — the GL.** `finance.JournalEntry` is an aggregate (append-only fact, `AGGREGATE_TYPE`, repository); the **gl-account balance is NOT an aggregate** — it's `SUM(debit - credit) FROM journal_entry_line WHERE gl_account_id = ?`. The same shape repeats for `customer_invoice_header.paid_amount` (trigger-maintained off `PaymentAllocation` rows) and `inventory.stock_balance` (writer-port-maintained off four upstream aggregates' events). In each case the aggregate is the thing that *emits a delta*; the non-aggregate is the thing that *holds the sum*.

**Historic audit.** The 2026-05-15 audit surfaced five `*Repository`-without-an-aggregate offenders; all resolved across §2.16–§2.18. The 2026-05-17 audit, prompted by formalising this section, surfaced one further violation: `inventory.StockItem`, which has the aggregate skeleton but emits zero events (every mutation is `applyReorderPolicy` driven by an inbound product-master fact, so it is structurally a *snapshot projection of upstream state* in the table above). Flagged for demotion as `dev-todo.md` §2.22; pull forward when an inventory-originated stock-fact slice (manual adjustment, stock-take, etc.) creates a legitimate first emitter.

## Instance-field naming: the full aggregate name in plural, not the class-kind suffix

The type already says what kind of collaborator it is (`Repository`, `Lookup`, `QueryPort`, `Writer`, `Projection`); the field name says what's *in* it. Concretely:

- **Use the full aggregate name in plural form** as the field name. `salesOrders` for `SalesOrderRepository`, `purchaseOrders` for `PurchaseOrderRepository`, `journalEntries` for `JournalEntryRepository`, `stockReservations` for `StockReservationRepository`, `goodsReceipts` for `GoodsReceiptRepository`, `supplierProductPrices` for `SupplierProductPriceRepository`, `bomEdits` for `BomEditRepository`, `productPricing` for `ProductPricingLookup`, etc.
- **Don't abbreviate to a context-implied short form.** `orders` is wrong when both `SalesOrderRepository` and `PurchaseOrderRepository` exist in the codebase — even in a single-service file, `salesOrders` reads unambiguously when grepped across the repo. Same for `invoices` (should be `supplierInvoices` / `customerInvoices`), `requisitions` (`purchaseRequisitions`), `pricing` (`productPricing`). The "no abbreviation" rule applies even when the local context makes the short form unambiguous — consistency across files is what matters.
- **Don't use generic kind-names** like `repository`, `repo`, `lookup`, `queryPort`, `projection`, `writer`. The type already conveys the kind. `repository.findById(...)` reads as JDBC-flavoured infrastructure code; `salesOrders.findById(...)` reads as a domain operation.
- **Class-kind suffixed names** are a last-resort disambiguation when a single class genuinely needs to inject both a `Writer` and a `Lookup` for the same data (`stockBalances` is the writer; the lookup gets a name like `balanceLookup` only because of the collision). Avoid otherwise.

Call sites reflect this directly: `salesOrders.findById(id)` says "find a sales order by id", which is what the orchestration is *doing*. The field-naming convention is what makes the application layer read like business code rather than data-access plumbing.

When two collaborators in a single class would naturally take the same name (e.g. `PaymentService` holds both `SupplierInvoiceRepository` and `CustomerInvoiceRepository`), qualify both with the full aggregate name: `supplierInvoices` + `customerInvoices`. Never use a bare `invoices` field — even if today only one kind is held, the qualifier ages well.

This applies to application-service-to-application-service composition too: `PaymentService.journalEntries` of type `JournalEntryService` follows the same rule (field name = the data the collaborator operates on, plural).

## Hexagonal 4-way rule — full why-each-direction-is-forbidden

(The table + greps live in `CLAUDE.md` for fast reference. Rationale below.)

- **`api/ → domain/`** — a controller holding an aggregate can silently call its mutators without going through the application service that drains pending events to the outbox. Strict ban removes the footgun structurally.
- **`api/ → infrastructure/`** — the controller would bind to a concrete JDBC class instead of a port; the database-per-service refactor (an architectural invariant of this codebase) would then require touching controllers.
- **`application/ → infrastructure/`** — orchestration would depend on SQL, which couples the use case to its persistence strategy.
- **`application/ → api/`** — application would depend on the wire format, so a JSON field rename would propagate inward. The wire shape is a boundary concern, not an orchestration concern.
- **`domain/ → anything except shared-kernel`** — domain is the most stable layer; any outward dependency would invert the dependency arrow and pull infrastructure-style concerns (Spring, JDBC, Jackson) into invariant-bearing code.
- **`infrastructure/ → api/`** — see the documented `@AutoConfiguration` exception in `CLAUDE.md`.

## `application/dto/` types at the wire boundary — YAGNI default

Most of the time the application's `*View` / `*Command` IS the wire format, and introducing a parallel `*Response` / `*Request` in `api/dto/` just duplicates fields without buying anything until the wire actually needs to differ. The layering rule that `api/ → application/` is allowed (but not the reverse) means a controller can directly return `*View` and accept `@Valid @RequestBody *Command` — both inputs and outputs flow through the application layer without an intermediate api-side mirror.

```java
// application/dto/SalesOrder360View.java — application-shape record
public record SalesOrder360View(UUID salesOrderHeaderId, ..., Instant updatedAt) {}

// application/SalesOrder360QueryPort.java — returns the View directly
public interface SalesOrder360QueryPort {
    Optional<SalesOrder360View> findBySalesOrderId(UUID id);
}

// infrastructure/persistence/JdbcSalesOrder360QueryPort.java — RowMapper produces View
@Repository
public class JdbcSalesOrder360QueryPort implements SalesOrder360QueryPort {
    @Override public Optional<SalesOrder360View> findBySalesOrderId(UUID id) { ... }
}

// api/SalesOrder360Controller.java — returns the View; no api/dto/ counterpart
@GetMapping("/{id}/360")
public ResponseEntity<SalesOrder360View> get(@PathVariable UUID id) {
    return port.findBySalesOrderId(id)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
}
```

Same shape on the write side: controllers take `@Valid @RequestBody *Command` directly, with Jakarta Bean Validation annotations living on the `*Command` record in `application/dto/`. Application/dto/ already imports Spring concerns (`@Transactional` flows through it transitively), so picking up Jakarta validation is not a new layer crossed — just an annotation set co-located with the input record.

```java
// application/dto/RecordSupplierInvoiceCommand.java — Bean Validation on the Command
public record RecordSupplierInvoiceCommand(
    @NotBlank @Size(max = 50) String internalInvoiceNumber,
    @NotNull UUID purchaseOrderHeaderId,
    @NotEmpty @Valid List<Line> lines,
    ...
) {
    public record Line(@NotNull UUID purchaseOrderLineId, @NotBlank String productSku, ...) {}
}

// api/SupplierInvoiceController.java — no api/dto/*Request needed
@PostMapping
public ResponseEntity<SupplierInvoiceView> record(@Valid @RequestBody RecordSupplierInvoiceCommand command) {
    SupplierInvoiceView view = service.recordInvoice(command);
    return ResponseEntity.created(...).body(view);
}
```

**When to introduce a separate `api/dto/` type:**

- The wire shape genuinely diverges from the application shape — e.g. extra wire-only fields, a different field name on JSON, or computed metadata that doesn't belong on the application View.
- The wire shape is **asymmetric** with respect to a path-binding controller — e.g. `CancelOrderRequest` (`{ reason }`) vs `CancelOrderCommand` (`{ salesOrderHeaderId, reason }`): the salesOrderHeaderId comes from the URL path, so the wire body must be the smaller `*Request` shape. `CompleteOperationRequest` ↔ `CompleteOperationCommand` is the same shape — Command receives the WO id + operation sequence from the path, Request carries only the body field.
- The api endpoint has no application-side analog at all — e.g. `AddBomLineRequest`, `CreateBomDraftRequest`, `ReverseBySourceRequest`, `ApprovePurchaseOrderRequest`, the `Change*Request` family on Customer / Product etc. — these encode arguments that the service takes as positional method params, with no `*Command` record on the application side.
- A pure wire-side response that synthesises data not on any single View — `AddBomLineResponse`, `CreateBomDraftResponse`, `ProductMaterialsCostResponse`, `ReverseBySourceResponse` — these have no projection-shape mirror and live in `api/dto/` only.

**Code-review rule:** an `api/dto/*Response` whose fields are a 1:1 mirror of an existing `application/dto/*View` (modulo inner-record names — `Line` vs `*LineView` is the same shape) is dead duplication. Delete the Response; controller returns the View. Same for `*Request` ↔ `*Command` when the shapes match.

## Controllers (`api/`) — full do-not-import list

Twin of the JdbcTemplate ban on the other side of the application layer. The rule is **strict**: zero `import com.northwood.<service>.domain.*` in any file under `api/`. The application layer is the **only** seam between API and the rest of the system. This holds for controllers, DTOs, exception handlers — everything under `api/`.

**Why strict (no domain types in `api/`, not even read-only access via DTOs).** Domain alone doesn't ensure integrity: the `Product` aggregate has mutator methods (`changeSalesPrice`, `discontinue`, `activateBom`) whose side effects only complete when the application service drives `repository.save(...)` — which in turn writes the pending events to the outbox in the same transaction. An aggregate held in scope by a controller — even one obtained via `service.findById(id)` for read-only mapping — is a half-thing whose mutators can be called silently:

```java
// nothing stops a future maintainer writing this — and nothing detects it:
Product p = service.findById(id).orElseThrow();
p.discontinue();   // mutates aggregate, never persisted, never emitted, silent corruption
return ProductResponse.from(p);
```

Application + domain (+ infrastructure for persistence) is the integrity boundary, not domain on its own. Exposing only domain to `api/` would be exposing half the boundary. The strict ban removes the footgun structurally — a controller that has only a `ProductView` literally cannot invoke a domain mutator, regardless of intent.

**Concretely, controllers do NOT inject or import:**

- **Domain aggregates** (`Product`, `SalesOrder`, `Customer`, etc.) — not as constructor parameters, return values from services, or DTO mapper inputs.
- **Domain VOs** (`ApprovedVendor`, `Money`, line records like `SalesOrderLine`, etc.) — not for constructing inputs to service commands either.
- **Domain identity VOs** (`SalesOrderId`, `WorkOrderId`, `PaymentId`, etc.) — services accept raw `UUID` and wrap to the identity VO internally on the first line.
- **Domain exceptions** in `@ExceptionHandler` — wrap with an application-layer exception on the service that catches the domain one.
- **`*Repository`** (domain ports) — even for thin read-only `findById`. Add to the application service.
- **`*Projection` from `application/inbox/`.** These are inbox-handler-only by the *Projection rule above; a controller that needs to read a projected row goes through a separate `*Lookup` / `*QueryPort` in `application/`.
- **Domain types nested inside repositories** (e.g. `SupplierProductPriceRepository.PriceRow`) as public method signatures — wrap in an application-layer record returned by the service.
- **`JdbcTemplate` or any `infrastructure/` type.**

**Acceptable in a controller:**
- Application service classes.
- Application-layer query ports / lookups (`*QueryPort` / `*Lookup` whose interfaces live in `application/`).
- Application-layer record types: `*View`, `*Command`, `*Query`, application exception types.
- `api/dto/*` records — request DTOs and response DTOs that map from application-layer Views, not domain types.

**All data-shaped records live in `application/dto/`** — `*View`, `*Command`, `*Request`. Services in `application/` hold only orchestration logic (use-case methods, exception types, helper services); data shapes sit in a sibling `application/dto/` sub-package.

```
<service>/application/
├── ProductService.java            ← orchestration only (use cases + exceptions)
└── dto/
    ├── ProductView.java           ← read-side projection (with from(Aggregate) mapper)
    ├── ApprovedVendorCommand.java ← input contract for a command method
    └── CreateProductRequest.java  ← multi-arg input shape (when one exists)
```

The split keeps `application/` focused on application/business logic and `application/dto/` as a flat namespace of "brainless data" — pure records mirroring or shaping aggregate state for the wire layer. Grep `Grep '*View\|*Command\|*Request' **/application/dto/` is the canonical "what shapes does this service expose?" lookup.

## The View pattern: aggregate → `*View` record in `application/dto/`

Every aggregate that has a read-side surface gets a `*View` record in `application/dto/`. The View carries a static `from(Aggregate)` factory that does the field copy (so domain mutation methods are never visible to the wire layer). Layout:

```java
// in application/dto/ProductView.java
public record ProductView(
    UUID productId, String sku, String name, ..., long version
) {
    public static ProductView from(Product p) {
        return new ProductView(p.id().value(), p.sku().value(), p.name(), ..., p.version());
    }
}

// in application/ProductService.java
@Service
public class ProductService {

    @Transactional(readOnly = true)
    public Optional<ProductView> findById(UUID productId) {
        return products.findById(ProductId.of(productId)).map(ProductView::from);
    }

    @Transactional
    public ProductView createProduct(...) {
        Product product = Product.register(...);
        products.save(product);
        return ProductView.from(product);
    }
}
```

Controllers return the View directly — no separate `api/dto/*Response` mirror unless the wire shape genuinely diverges (see the YAGNI rule above).

Master-detail aggregates get a sibling `*LineView` in the same `dto/` package (e.g. `SalesOrderView` + `SalesOrderLineView`, `WorkOrderView` + `WorkOrderMaterialView` + `WorkOrderOperationView`). The aggregate → View mapping is a flat field copy; domain methods are never invoked from `api/`.

## The Command pattern: domain VOs constructed by the service, not the controller

When a service command takes a domain VO as input (e.g. `setApprovedVendors(UUID, List<ApprovedVendor>)`), introduce an application-layer `*Command` record in `application/dto/` so the controller passes wire-shaped data and the service constructs the domain VO internally:

```java
// in application/dto/ApprovedVendorCommand.java
public record ApprovedVendorCommand(
    UUID supplierId, String supplierCode, String supplierName, boolean preferred
) {}

// in application/ProductService.java
@Service
public class ProductService {
    public void setApprovedVendors(UUID productId, List<ApprovedVendorCommand> vendors) {
        // map ApprovedVendorCommand → domain ApprovedVendor inside the service
        List<ApprovedVendor> mapped = vendors.stream()
            .map(v -> new ApprovedVendor(v.supplierId(), v.supplierCode(), v.supplierName(), v.preferred()))
            .toList();
        // ...
    }
}
```

`*Command` records may share the same field shape as the corresponding domain VO today; that's fine. The two types are allowed to diverge independently (api/wire concerns vs domain invariants).

## Identity at the API boundary: raw `UUID`, wrap inside the service

Service public methods accept `UUID`, not `*Id`. The wrap to the identity VO happens on the first line of the service method:

```java
public Optional<ProductView> findById(UUID productId) {
    return products.findById(ProductId.of(productId)).map(ProductView::from);
}
```

This removes the identity-VO import from controllers and keeps the type-safe identity VOs flowing through `application/` and `domain/` internally.

## Exception wrapping — three flavours

All keep `api/` on application types only:

1. **Infrastructure-thrown, application-defined.** The exception class lives on the application service; infrastructure throws it directly. Exemplar: `CustomerService.DuplicateCustomerCodeException` thrown by `JdbcCustomerRepository`.
2. **Domain-thrown, application-wrapped.** When an aggregate throws a domain exception (e.g. `SalesOrder.OrderNotCancellableException`, `PurchaseOrder.PoNotApprovableException`), the application service catches it inside the use-case method and rethrows an application-layer counterpart (`SalesOrderService.OrderNotCancellableException`, `PurchaseOrderService.PoNotApprovableException`) that preserves message + cause. Controllers catch only the application version.
3. **Domain-port-thrown, application-wrapped.** Same pattern when a domain port (e.g. `CurrencyConverter.RateNotFoundException`) is invoked from the application service; the service catches + rethrows (`ExchangeRateService.RateNotFoundException`).

## Command return shape

Command services return a `*View` directly — controller skips any refetch query:

```java
ProductView view = service.createProduct(...);
return ResponseEntity
    .created(URI.create("/api/products/" + view.productId()))
    .body(ProductResponse.from(view));
```

When a command's side effects span multiple aggregates (e.g. `PurchaseRequisitionService.createManual` triggers PR→PO conversion that flips the PR's own status via a different aggregate load), the service still returns a `*View` but reloads the primary aggregate first so the View reflects the post-side-effect state.

## Architectural payoff (why all of the above is worth it)

- The seam between `api/` and the rest of the system is one layer thick (the application service). A refactor that splits the database-per-service touches `application.yml` only — no `api/` change needed.
- The "domain alone is not an integrity boundary" insight is enforced structurally: a controller can't accidentally call a domain mutator because it never has the type in scope.
- Wire-shape evolution and domain-shape evolution are decoupled by the application layer: a domain field rename touches the aggregate + its `*View.from(...)` mapper. The wire format is the View unless a controller has an explicit `api/dto/*Response` (introduced only when shape genuinely diverges) — at which point that Response carries the wire-only fields and renames.

## Class member ordering — static fields on top, strictly

Inside any class body, **every `static` field declaration must appear before any non-static (instance) field declaration**. The rule is strict: it applies to `public static final` constants, `private static final` SQL query strings, `private static final RowMapper<X>` lambdas, `private static final Comparator<X>`, and any other static field regardless of accessibility or whether it's "constant-shaped" or "function-shaped". A `static` field that sits below an instance field is a code-review fail.

This follows the historical Oracle Java Code Conventions (1999) field-ordering convention, also enforced by default Checkstyle / IntelliJ "Rearrange Code" / Google Java Style — i.e. `static fields → instance fields → constructors → methods`. It's the most widely-followed convention in the Java ecosystem and reads consistently with the rest of the JDK / Spring source. The strict-no-exception variant is what this codebase commits to so that a `RowMapper` sitting at the bottom of a `Jdbc*Repository` is unambiguously a rule violation rather than a debatable judgement call.

```java
// CORRECT — all statics at top
@Repository
public class JdbcCustomerRepository implements CustomerRepository {

    private static final RowMapper<Customer> ROW_MAPPER = (rs, n) -> Customer.reconstitute(...);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcCustomerRepository(...) { ... }

    @Override public Optional<Customer> findById(CustomerId id) { ... }
    // ... other instance methods
}

// WRONG — RowMapper as last member, after instance fields and methods
@Repository
public class JdbcCustomerRepository implements CustomerRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    // ... instance fields, constructor, methods ...

    private static final RowMapper<Customer> ROW_MAPPER = (rs, n) -> ...;  // ← fail
}
```

**Static methods are not constrained by this rule.** Group them by what they do, not by their modifier:

- Static factory methods (`Customer.register(...)`, `SalesOrder.place(...)`) sit near the constructor — they're the public minted-aggregate entry points and read best alongside the private constructor they wrap.
- Private static helpers (validation, format conversion, builder shorthand) can sit near the bottom or near the only method that uses them — wherever the local narrative reads cleanest.
- Public static utility methods on a utility class follow whatever the class's own grouping convention is.

**Other ordering rules — already conventional in this codebase, listed here for completeness:**

- **Nested types** (`static class`, `static enum`, `interface`) at the **top** of the class body, above all fields. The `Customer.Status` enum at the top of `Customer.java` is the exemplar. Public nested types declared inside the aggregate they belong to (e.g. exception classes nested on services) follow the same rule.
- **Instance fields** after statics and nested types, before constructors.
- **Constructors** after instance fields, before methods. Private constructors (with public static factories above them in the methods section) follow the same rule.
- **Methods** last, grouped by functionality, not by visibility. Static factories sit near the top of the methods section (next to the private constructor they wrap), then public mutators/queries, then private helpers.

**Machine-checkable canary** (run from repo root):

```
# Find any .java file where a `static` field declaration sits below a non-static field.
# Manual: visually inspect each class body. Each *Jdbc* repository, each *Test class with
# static UUIDs, each *QueryPort with a SQL constant — they all read top-to-bottom and the
# eye picks up "static after non-static" easily.
```

There's no canned grep that catches this perfectly — but the visual inspection pass on each file under audit is cheap, and the rule's strictness means no judgement call is needed.

## Document silent fallbacks at both source and sink

When a method substitutes a sentinel / default value rather than throwing on a missed match — null `Optional`, missing `Map` entry, ID lookup miss, race on a stale read, projection that hasn't caught up yet — the substitution **must** be:

1. **Justified in a method-level Javadoc on the emitter.** State the trigger ("when X is null", "when ID match fails"), the substituted value (`BigDecimal.ZERO`, generic 1200 account, `Instant.now()`, etc.), the rationale (saga must keep flowing on a fait-accompli inbox event; projection-order-tolerance; throw would freeze a downstream flow), and why throwing isn't the right call today. List the named tightening alternatives a future reader should consider when the fallback stops being acceptable (throw, fall back to a different lookup, validate-and-reject earlier in the flow).
2. **Cross-referenced in a Javadoc on the consumer that trusts the substituted value**, naming the emitter so a reader following the flow doesn't have to re-derive the contract. If the consumer is only one method on the same class, one line is enough; if it's a separate handler / service / projection in another module, the cross-ref carries the named link.
3. **Logged when the fallback fires.** DEBUG when the fallback is the designed-tolerant path (projection catch-up, currency default, dead-defensive ID match against a populated end-to-end source). WARN when the fallback signals data corruption or an unexpected race (null where a NOT NULL invariant should hold; row disappears mid-loop). The log line names the entity ids + the field that fell back, never just "fallback triggered".
4. **Indexed in `design-notes.md` under *Documented silent fallbacks*.** Add a row to the table: emitter (file:method), trigger, substitution, log level, downstream consumer, tightening alternative. The table is the canonical "what we tolerate, on purpose, today" — code review uses it as a checklist, and a fallback that exists in code but not in the table is the same code-review fail as one without a Javadoc. When the underlying code changes, refresh the row in the same PR.

The exemplar is `SalesOrder.recordShipped` ↔ `CustomerInvoiceService.createFromShippedOrder` (sales-service / finance-service): an unmatched `salesOrderLineId` substitutes `lineNumber=0`, `unitPrice=ZERO`, `taxRate=ZERO` (saga must keep flowing on a fait-accompli shipment); the consumer detects `lineNumber == 0` as the sentinel and emits one DEBUG log per invocation when the count is non-zero. The other five compliant sites are listed in the `design-notes.md` table; refer there rather than duplicating here so the index stays single-sourced.

The rule applies to fallbacks anywhere in the codebase. Code review should treat an undocumented `.orElse(SENTINEL)` / null-coalescing-to-default — or one missing from the `design-notes.md` index — as a fail.

## Single return path; make every branch exhaustive

Two related rules for any method with branching logic:

**1. Converge to a single `return` at the bottom, except for early-return guards.** Early-return is for *bailing out* — a precondition miss, a "nothing to do" no-op, a special case with no shared work afterwards. A genuine N-way business split (two or more paths that all do meaningful work) should use `if / else if / else` and fall through to one terminal `return` at the bottom. The reader's mental model — "where is this method's output?" → "scroll to the bottom" — stays intact; the early-return pattern is a deliberate deviation reserved for true bail-outs.

**2. Make every branch exhaustive — `throw` rather than silently coerce on a violated input contract.** When a method has a documented input contract ("for `status == X`, `paramY` must be non-null and non-empty"), enforce it with a `throw new IllegalStateException(...)` whose message names the offending value(s) plus the contract sentence. The reader sees the invariant + the bad data at the call site, not stitched together from a downstream WARN.

This is the anti-side of the silent-fallback rule above. Silent fallbacks are tolerated *only* when documented in the `design-notes.md` index; contract violations on inputs from internal collaborators (other services, the saga manager's own callers, anything inside the bounded-context boundary) are **not** fallback candidates — fail loudly. Both rules push the same goal: the reader doesn't have to guess what the method does for the cases the code doesn't visibly cover.

Exemplar: `JdbcSalesOrderFulfilmentSagaManager.applyStockReserved`. The `RESERVED` branch (transition to `READY_TO_SHIP`, skip manufacturing) and the `else` branch (partial / failed → stash shortage, transition to `STOCK_RESERVED`) both fall through to one `return saga.state();` at the bottom. Inside the `else`, an `IllegalStateException` enforces "partial / failed must carry a non-empty shortage map", with `reservationStatus` + `salesOrderHeaderId` + the contract sentence baked into the message. No silent stashing of empty data; no need to grep `readShortage` or the worker's WARN guard to learn what should have happened.
