# design-notes.md

Durable architectural notes that don't fit `CLAUDE.md` (rules / conventions) or
`dev-done.md` (slice changelog). Living reference — refresh entries when the
underlying code changes; remove entries when the underlying decision no longer
applies.

---

## Documented silent fallbacks

Sites where a method substitutes a sentinel / default value rather than
throwing on a missed match. Each site is compliant with the silent-fallback
rule in `CLAUDE.md` (Architecture → *Document silent fallbacks at both source
and sink*): method-level Javadoc on the emitter, runtime log when the
fallback fires, named tightening alternative for the day the fallback stops
being acceptable.

When you change any of these sites, update both the file Javadoc **and** the
row below — the table is the canonical index of "what we tolerate, on
purpose, today".

### Index

| # | Emitter (file:method) | Trigger | Substitution | Log level | Downstream consumer (trusts the substituted value) | Tightening alternative |
|---|---|---|---|---|---|---|
| 1 | `sales-service` `SalesOrder.recordShipped` | inbound `ShippedLineInput.salesOrderLineId` is null or doesn't match any line on the aggregate | `lineNumber = 0`, `unitPrice = ZERO`, `taxRate = ZERO` on the emitted `ShippedLine` | **DEBUG** (in consumer; once per invocation if any zeros) | `finance-service` `CustomerInvoiceService.createFromShippedOrder` — emits a zero-amount invoice line + zero AR/Revenue GL pair | Throw at the emitter; or fall back to a `ProductCardLookup` to emit a real number |
| 2 | `manufacturing-service` `WorkOrderOperationService.emitSubAssembliesConsumedIfParent` | child WO has `status='completed'` but `completed_quantity` is null (data-corruption signal — invariant violated) | child silently dropped from `SubAssembliesConsumed.items` | **WARN** | `inventory-service` `SubAssembliesConsumedHandler` → `WipBalanceWriter` — that child's WIP is not decremented | Add a NOT NULL CHECK invariant on `manufacturing.work_order(completed_quantity)` when `status='completed'`; recovery script if the constraint catches a violation |
| 3 | `manufacturing-service` `WorkOrderCancellationService.cancelForSalesOrder` | WO id returned by `findActiveIdsForSalesOrder` not resolvable by `findById` (race against a concurrent delete; theoretical today — no path deletes WOs) | WO silently skipped; `ManufacturingSalesOrderCancellationApplied.workOrdersCancelled` count short by N | **WARN** | `sales-service` `ManufacturingCancellationAppliedHandler` — uses the message as a boolean ack, not the count, so the under-count doesn't bite saga progress today | Tighten when WO deletion becomes a real code path; today's status-flag (`'cancelled'`) approach already keeps the row resolvable |
| 4 | `sales-service` `SagaCompensationCompletionService.completeIfReady` | `SalesOrder` aggregate not found when finalising compensation (shouldn't happen — `cancel(...)` already persisted) | `cancelledAt = Instant.now()` instead of original cancel time on `SalesOrderCompensated` | **WARN** | The `cancelledAt` field on the event (informational); reporting projects `payload.occurredAt()`, so today's projection is unaffected. Future audit-log consumer reading `cancelledAt` would see drift by saga round-trip duration | None — should never happen; fix the cause if WARN ever fires |
| 5 | `purchasing-service` `PurchaseOrderService.buildLines` | no `supplier_product_price` row for (supplier, product, currency, effective_date) | `unitPrice = BigDecimal.ZERO` → zero `line_total` → likely zero `total_amount` on the PO | **WARN** | Reporting `purchase_order_tracking_view` shows zero `ordered_amount`; finance 3-way match parks the matching invoice at `three_way_match_failed` for manual review (the *intended* loud-surfacing of a missing price) | Validate-and-reject at PR-conversion time once supplier price coverage matures (won't be acceptable to send a zero-cost commitment to a real supplier) |
| 6 | `finance-service` `JournalEntryService.inventoryAccountForProduct` / `cogsAccountForProduct` | `finance.product_card.valuation_class` is NULL for the product yet (event-stream race during burst-receive on a fresh-volume boot) | inventory → 1200 (generic Inventory), COGS → 5000 (generic COGS); also fires on unrecognised valuation_class string | **DEBUG** (designed — this is the projection-order-tolerant path) | `journal_entry_line.account_code` — generic-1200 / generic-5000 postings appear in the GL | Manual re-classification entry once the projection catches up (no automated reclassification today; would need a periodic sweep job) |
| 7 | `finance-service` `ShipmentPostedCogsHandler.handle` (COGS standard cost) | `finance.product_card.standard_cost` is NULL for the productId (cold-start race on a fresh volume before the seed Liquibase changeset applies, or before the inbox handler catches up) | per-line `unitCost = payload.unitCost` (the warehouse clerk's value off the shipment line) | **DEBUG** (one summary line per shipment when count > 0) | `journal_entry_line.debit_amount` / `credit_amount` on the COGS pair — the GL captures the event-stamped cost rather than finance's authoritative one | The seed changeset bootstraps day-1 coverage; for new products, sequence the projection update ahead of the first ship. Tighten by throwing if coverage gaps become a real concern (would freeze the COGS posting until the projection populates). |

### Operating notes

- **DEBUG vs WARN.** DEBUG is for fallbacks that are part of the *designed* tolerance (projection catch-up, fait-accompli inbox event with end-to-end-populated source data, currency default). WARN is for fallbacks that signal an unexpected condition: invariant violation, race, missing aggregate that shouldn't be missing.
- **Logs always name entity ids + the field that fell back.** Never just "fallback triggered". The point is that someone tailing logs in production can correlate the message back to the affected row.
- **Severity isn't fixed forever.** As coverage matures (supplier price list grows, projection bootstrap order changes), some DEBUG paths may stop being acceptable; the tightening-alternative column is the named follow-up.
- **The `lineNumber == 0` sentinel for #1.** Real `SalesOrderLine` rows have `lineNumber >= 1` by aggregate construction; the emitter's `matched != null ? matched.lineNumber() : 0` is what makes this detectable downstream. If `SalesOrderLine.lineNumber` ever becomes legitimately zero-valued, this signal stops working — flag at that change.

---

## Snapshotted reference data

Reference-data fields that *some* aggregates copy onto themselves at creation
time and never refresh. This is a deliberate design choice — not a fallback
or a limitation. Each entry below records what's snapshotted, why, and the
named alternative (in case demand for rippling ever appears).

### Customer name + code on `sales.sales_order_header`

**Snapshotted fields:** `customer_id`, `customer_code`, `customer_name`.
**Captured by:** `SalesOrder.place(...)` at order-placement time.
**Refreshed when customer renames?** No.
**Downstream views:** reporting's `sales_order_360_view.customer_name`
mirrors the order-stamped value (event-driven from `SalesOrderPlaced`),
so it shares the same snapshot semantics — also not refreshed on
`CustomerNameChanged`.

**Why snapshot, not ripple.** The order shows the name at time of placement.
That's the audit-correct ERP behaviour: a customer who later rebrands or
merges shouldn't have their old paperwork rewritten. The legal name on the
invoice has to match the legal name when the order was placed, not the
current one. Same reason `created_at` doesn't move when a row updates.

**Locked 2026-05-08** during the Customer-aggregate slice. The
`CustomerNameChanged` event is still emitted (so a future consumer that
*does* want a directory view can subscribe), but no current consumer
projects it.

**Tightening alternatives, in order of escalation if a use case appears:**

1. **Project to a customer directory view** — new reporting projection
   (e.g. `reporting.customer_directory(customer_id, current_name, …)`) that
   tracks the live name. UI screens that want the current name read from
   there; sales-order detail keeps reading the snapshotted name from the
   order. Net new column count: 0; net new view: 1. Cleanest.
2. **Re-project rippling** — handler for `CustomerNameChanged` updates
   `sales_order_360_view.customer_name` for matching `customer_id`. Loses
   the audit invariant (historical orders now show the new name). Only
   pursue if the directory-view option proves insufficient.
3. **Annotated snapshot** — add `customer_name_at_placement` (snapshot)
   alongside `customer_name_current` (live, joined from the directory
   view). Extra column on every reporting row; pretty heavyweight. Don't
   pursue unless both #1 and #2 hit a real wall.

### Why this is a section, not a fallback

The silent-fallback section above is for situations where a missed match
gets a substituted value. Snapshotting is different — it's a *deliberate
freeze*: the order knows the customer's identity (id), and the displayed
fields are point-in-time captures by design. There's no miss to fall back
from. We document them in the same file because both are durable
"behaviour-encoded-as-data" decisions a future reader might mistake for a
bug.

---

## Lessons learned

### Nested-event-record as transport type — only a smell when it crosses layer boundaries

**From:** §2.7 (2026-05-08), where `ApprovedVendorListChanged.ApprovedVendor` (a record nested inside the event class) was used as the parameter type on `Product.emitApprovedVendorListChanged(...)`, on `ApprovedVendorRepository.findForProduct/replaceFor`, on `JdbcApprovedVendorRepository`'s row mapper, on `ProductService.setApprovedVendors`, and constructed by `ProductController.setApprovedVendors`. The event class leaked through every layer purely to thread the type. Fix: promote the nested record to a top-level domain VO at `domain.ApprovedVendor`; the event references the VO; everyone else uses the VO.

**The audit afterwards** found 14 other nested-record imports in the codebase. **None matched the §2.7 shape**, because in each case the nested record stays *local to one method*: build a `List<NestedRecord>`, populate, hand to the event constructor, no parameter / return / field exposure. Auto-promoting all 14 would add 14 record files to remove a coupling that doesn't propagate — net negative for readability.

**The smell is layer propagation, not the import itself.** A grep for `events\.X\.Y` finds candidates; the question to ask each is:

| Pattern | Verdict |
|---|---|
| Aggregate's public method takes `EventName.NestedRecord` as parameter, repository / controller / service signatures use the same type | **Smell.** Promote to a top-level domain VO. |
| Aggregate or saga-side service constructs `new EventName.NestedRecord(...)` inside one method, only as event payload | **Fine.** Local construction; no signature exposure. |
| Inbox handler / saga worker imports a nested record from its *own service's* event for direct emission via `OutboxRow.pending` | **Fine.** Sanctioned "saga-side observation" pattern (CLAUDE.md). |
| Inbox handler imports a nested record from *another service's* event payload (`*Payload.java`) | **Different concern entirely.** Cross-service `*Payload` classes are owned by the consumer; not a smell. |

**Detection heuristic for the next audit.** The grep pattern `events\.\w+\.\w+` finds the candidates. Triage by checking method signatures only — if the nested type appears in a `public` method's parameter or return type AND is used outside the file that declares the event, it's the §2.7 shape. Otherwise it's local construction.

**Why this matters.** The original §2.7 audit (in dev-todo at the time) called out two outliers based on import lists alone. Looking at imports gives a candidate set; only signature analysis tells you whether the import is a transport leak or a local convenience. The same lesson applies to other "X imports Y from a forbidden package" rules — the import is the trigger to investigate, not the verdict.

### Computed values live with the engine that computes them, not the master aggregate

**From:** §2.8 Slice C (2026-05-08), where `materialsCost` was originally proposed as a field on the `Product` aggregate (in product-service). The natural consumer for the rolled-up cost is the product detail page — same shape as `salesPrice` and `standardCost` — so putting it on the master felt right.

**The problem:** product-service is producer-only by architectural contract — it never consumes cross-service events. Adding `materialsCost` to `Product` would have forced product-service to subscribe to `purchasing.SupplierProductPriceChanged` (and eventually `manufacturing.ProductMaterialsCostComputed` for the BoM rollup), promoting it from upstream Open Host to a hybrid. That's a one-way ratchet — once product-service has even one inbox handler, the invariant is gone forever.

**The fix:** materialsCost lives on `manufacturing.product_card` — manufacturing-service owns the rollup engine *and* the table, and emits `manufacturing.ProductMaterialsCostComputed` as the public contract. The UI fetches `GET /api/products/{id}/materials-cost` from a manufacturing endpoint; the BFF stitches it into the product detail page client-side via a parallel query. product-service stays pure producer.

**The general rule.** A computed value (rollup, derived metric, lookup-fed cache) belongs in the service that computes it, not the service that owns the source of the inputs and not the service that owns the master record it appears alongside. Specifically:

| Field shape | Owner |
|---|---|
| Master data — keyed by id, edited by humans, no compute (sku, name, salesPrice, standardCost) | Master aggregate (product-service) |
| Computed cache — derived from N input events from M services (materialsCost, weighted-average cost, on-hand quantity) | The engine that computes it (manufacturing, finance, inventory) |
| Snapshot — captured at a moment, never updated (customerName on sales_order_header, salesPrice on order_line) | Wherever the snapshot is taken |

**The tell** that you're about to violate this: you find yourself wanting to add a subscribe-topic to a service whose `application-kafka.yml` currently has `subscribe-topics:` empty / single-line. If the only reason to subscribe is "so I can store this one computed thing on my aggregate", that's the moment to step back and ask whether the value belongs somewhere else.

### Cross-context aggregate-root stamping on events

**From:** the 2026-05-14 `event-flow.html` audit, which surfaced two events stamped with an `aggregateType` from a context other than the emitting service: `manufacturing.ManufacturingDispatched` stamped `SalesOrder` and `manufacturing.ProductMaterialsCostComputed` stamped `Product`. Surface reading made these look like pragmatic compromises; a closer look established they're correct modeling.

**The rule.** An event names the aggregate the fact is *about*, not the module that emits it. Logical ownership and physical emission are decoupled — any module that has the knowledge to assert the fact *and* the contract authority to publish under the aggregate's namespace can emit it. This is orthodox DDD properly read; the classical guidance has always been about which aggregate the event identifies, not where the emitting code physically lives.

**Three-criterion test for cross-context emission:** legitimate when all three hold:

- **(a) Subject match** — the named aggregate is genuinely what the fact is about, not merely correlated with it.
- **(b) Source authority** — the emitter is the natural source of knowledge (inputs no other module can synthesize from its own state).
- **(c) No invariant claim** — the emitter isn't claiming jurisdiction over the named aggregate's lifecycle or business rules.

**Northwood passes for both today's cross-context events:**

- `ManufacturingDispatched` → `SalesOrder`. Manufacturing learns per-line accept/reject by joining its `product_replenishment` projection + active-BOM lookup (knowledge only it has, (b)); the fact is what the SalesOrder needs to advance the fulfilment saga ((a)); manufacturing doesn't claim SalesOrder state-machine authority, it just reports an outcome ((c)).
- `ProductMaterialsCostComputed` → `Product`. Manufacturing owns the rollup engine and has the inputs (vendor prices + active BOM), so it's the source of authority ((b)); the conclusion is a fact about a Product ((a)); manufacturing doesn't claim Product lifecycle authority ((c)).

**Counter-examples that would fail.** "Manufacturing emits `ProductDiscontinued`" violates (b) and (c) — manufacturing has no special knowledge of *why* a product should be retired, and discontinuation is a Product lifecycle decision. "Purchasing emits `SupplierBlocked`" is the same shape. Such cases should emit a producer-owned event (e.g. `BlockingRecommended`) and let the aggregate's owner consume it and decide whether to flip lifecycle state.

**Relationship to the [computed-values-live-with-the-engine](#computed-values-live-with-the-engine-that-computes-them-not-the-master-aggregate) entry above.** That rule is about where the *table* lives (manufacturing.product_card stays in manufacturing). This rule is about what the event's *aggregateType* points to (the event about that cost names `Product`). Complementary: the table is owned by the computing engine; the event names the aggregate the fact is about. Both rules together let manufacturing-service keep its rollup engine + table local while publishing a public contract that consuming services can integrate against using the same `aggregateId`-keyed flow they use for product-owned events.

**Happy consequence: Kafka partition co-location.** Because `aggregate_type` + `aggregate_id` together drive the Kafka partition key, cross-context stamping naturally co-locates every event for a saga's correlation aggregate onto one partition (`StockReserved` → `WorkOrderCreated` → `ManufacturingDispatched` → `ShipmentPosted` all keyed by the same SalesOrder id). The sales-fulfilment saga consumes the lot in order without cross-partition joins. This is a payoff of correct modeling, not the *reason* for the choice — the modeling rule stands on its own.

**Reference detail** lives in `architecture.md` under *Aggregate-root stamping: an event names the aggregate the fact is about*.

### Vendor-flip recompute deferred (§2.8 Slice C limitation)

**Locked 2026-05-08 as the Slice C scope.** The materialsCost rollup engine reacts only to `purchasing.SupplierProductPriceChanged`. A `product.ApprovedVendorListChanged` that flips the preferred supplier does *not* immediately trigger a recompute — the cost reflects the previous preferred supplier's price until the new preferred supplier emits its first `SupplierProductPriceChanged`.

**Why it's acceptable for the showcase.** Vendor-list edits are infrequent and supplier prices change frequently enough that the lag bound is small in practice. The alternative — recomputing on every `ApprovedVendorListChanged` — requires manufacturing to also project the supplier price list, doubling the projected state for a benefit that rarely matters. We picked the simpler path.

**Tightening alternative when it matters:** add a `SupplierProductPrice` projection in manufacturing (mirroring `ApprovedVendorList`), have the rollup engine consult it on `ApprovedVendorListChanged`, and emit a recomputed `ProductMaterialsCostComputed` immediately. Estimated cost: one new projection + handler + ~30 lines in `MaterialsCostRollupService`. Don't pull this forward speculatively.

### MaterialsCost rollup: routing, recursion, currency policy (§2.8 Slice D)

**Routing rule (BoM wins):** the rollup engine routes a recompute by checking active-BoM presence first. If a product has an active BoM, materialsCost is the BoM rollup — `Σ (qty * (1 + scrap%/100) * componentMaterialsCost)` across lines. Supplier prices for that *parent* product are ignored (the parent's cost is determined by its components). Supplier prices for *components* still flow: a `SupplierProductPriceChanged` on a leaf updates the leaf's cost, which walks up to recompute the parent.

**Why "BoM wins" rather than "min(BoM, supplier)"** — picking the lower of the two would be wrong as a default. A finished good with both an active BoM and a stocked supplier-purchased version is two different products from a costing perspective: "make-or-buy" decisions are deliberate human choices made at the `is_purchased`/`is_manufactured` flag level. The cost basis follows the same authority — once a BoM is active, the engineering structure is the truth; what a supplier happens to charge for the same SKU informs procurement, not standard cost.

**Parent recursion:** after writing a product's new cost, the engine queries `BomLookup.findParentProductIdsByComponent(productId)` and recursively recomputes each parent in the same transaction. Parents always have active BoMs by construction (a "parent" in this graph means a product whose active BoM lists the changed product as a line). The walk is depth-first, guarded by a visited set to absorb diamond-shape graphs (B and C both depend on A; D depends on both B and C — D is recomputed only once per chain).

**Cycle protection:** the BoM cycle detector (`BomCycleDetector`) already prevents cycles at edit time. The rollup walk's visited set + `MAX_WALK_DEPTH=32` are defensive belt-and-braces — they can't be load-bearing because BoM is acyclic by enforcement, but they prevent a corrupted state from causing an infinite loop.

**No-op suppression:** if the recomputed cost matches the existing row's cost+currency+reason, the projection write and outbox emission are skipped. This is essential for the parent walk: without it, a re-trigger of the same supplier price (e.g. inbox at-least-once redelivery) would cascade through the entire BoM tree even though nothing changed. The compare uses `BigDecimal.compareTo` (so trailing-zero differences are ignored) and string equality on currency/reason.

**Currency-mismatch policy (locked 2026-05-08):** the BoM rollup throws `IllegalStateException` when components in the same BoM are priced in different currencies. The thrown exception rolls back the whole inbox handler / activation transaction, so a single corrupted state can't half-apply. The throw is surfaced to operators rather than silenced because cross-currency rollup is a meaningful business decision (which rate? what date? whose system of record?) — defaulting to a silent conversion would hide the choice.

**Locked alternative for Slice E:** add a `CurrencyConverter` (already exists in finance-service; needs a manufacturing-side instance) + a `CurrencyPolicy` parameter on the rollup. The throw becomes `convert via CurrencyPolicy.targetCurrency(productId, occurredAt)`. The default target is the company base (AUD for the showcase). Estimated cost: ~1 day for the converter wiring + tests; gated on the throw actually firing in the demo dataset (today everything is AUD).

**Trigger surface (Slice D):**

1. `purchasing.SupplierProductPriceChanged` (Slice C trigger, BoM-aware in Slice D — see routing rule).
2. `product.ActiveBomChanged` via the inbox handler (cross-service path; manufacturing receives this when product-service's `Product.activateBom(...)` fires).
3. `BomService.activate(...)` (in-service path; the actual code path most demos exercise — direct REST call to manufacturing).

Both BoM-activation paths call `MaterialsCostRollupService.recomputeViaBom(productId, "bom_activated")` in the same transaction as the activation. BoM line edits (add/remove/change-quantity on draft) are *not* triggers — drafts don't affect active rollup; activation is the single point at which a draft's structure becomes visible.

**What's intentionally not a trigger (Slice D):**

- `BomDeactivated` / explicit deactivation flow — there's no deactivation command today (single-active-per-product, swap by activating a sibling draft which the partial unique index makes the old one inactive). When deactivation lands, the rollup needs a new reason `bom_deactivated` and a route that flips materialsCost back to the supplier-price path (or inputs_missing).
- `MakeVsBuyChanged` — flipping `is_purchased` doesn't change the routing because BoM presence already decides. Re-flagging a product purchased while it has an active BoM doesn't move the cost; deactivating the BoM would (see above).
- `ApprovedVendorListChanged` — the vendor-flip-recompute-deferred limitation above applies in Slice D too.

### Consumer-side projection for write-side validation, not sync REST

**From:** §1B.9 hardening #1 (2026-05-12), the shipment-line / receipt-line product validation slice.

The validation needed inventory to check, on every `POST /api/shipments` and `POST /api/goods-receipts`, that each line's `(salesOrderLineId, productId)` (or `purchaseOrderLineId, productId`) pair matched the originating SO / PO. The data lived in `sales.sales_order_line.product_id` and `purchasing.purchase_order_line.product_id` — both invisible to inventory under the per-service `search_path = inventory, shared` invariant.

**The tempting wrong answer:** add a sync REST endpoint on sales-service (`GET /api/sales-orders/{id}/lines/{lineId}`) and have inventory call it. Less code; no new tables; no new events.

**Why we didn't:** "the only inter-service contract is the outbox" is one of the load-bearing rules in `CLAUDE.md`. Once one cross-service sync call exists "just for this lookup", the next one is easier to justify — every "quick lookup" becomes part of the deployment graph, and the database-per-service migration story stops being a configuration change. The architectural cost compounds; the per-slice cost feels minor in isolation.

**What we did:** inventory consumes `sales.SalesOrderPlaced` and `purchasing.PurchaseOrderCreated` (events that already existed and already carry full line shape) and projects `inventory.sales_order_line_facts` / `inventory.purchase_order_line_facts` keyed by line id. The validation does a local `SELECT product_id FROM ...` keyed by line id. Same pattern as `finance.purchase_order_line_facts` (used for 3-way match — that one has a richer shape; inventory's are narrower because the only consumer is the validation predicate).

**The rule of thumb when this question recurs:**

| Consumer needs to know X about an upstream entity at write time | Right answer |
|---|---|
| X is **already on the event** the consumer subscribes to | Project a row keyed by what the consumer's writes already reference (line id, header id, etc.); validate locally |
| X is **derivable from N events** the consumer already projects | Compute on the fly from the projected state (no new table needed) |
| X exists upstream but is **not on any event today** | Either: (a) extend the producer's event to include X (additive — Jackson 3 tolerates unknown fields, so consumers ignoring X don't break); or (b) project a snapshot from a new event the producer emits on first creation. Avoid sync REST. |
| The validation is **a read-only check on a thing that only the producer's domain logic can decide** (e.g. "is this customer permitted to place orders right now?") | Stronger case for sync REST — but still question whether the answer could be projected. Genuinely tight coupling here is rare. |

The narrowest-possible-projection rule still applies: `inventory.sales_order_line_facts` is only `(line_id, header_id, product_id)`, not a 1:1 mirror of `sales.sales_order_line`. The consumer projects only what it needs to validate.
