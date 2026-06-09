# Composed state machines — when a parent's state is a function of its children

A formal model for the recurring ERP shape *"the state of a master record is composed
from the states of its detail records"* — a sales order over its lines, a purchase order
over its receipts, a customer invoice over its payment allocations, a work order over its
material lines. This note names the two formally-distinct composition models, shows which
one ERP master-detail actually is, gives the algebra for it, and ties it to Northwood's
existing invariants (`deltas get aggregates, totals get projections`; the DDD aggregate
root + read-model projection split).

> Status: **adopted convention** — opened 2026-06-09 as a design note, implemented and adopted
> 2026-06-10 (§2.29); registered in `CLAUDE.md` → *Pointers*. Consult it when modelling any
> master-detail status / completion rollup. The worked example uses the real
> `SalesOrder.Status` / `SalesOrder.LineStatus` enums. **Implemented (§2.29, 2026-06-10):** the
> header ship-axis fold is `SalesOrder.recomputeStatus()` — the single writer of `status`, with
> `markReserved` wired and the blind projection retired (§14.2 gaps 1 & 2 closed). The multi-axis
> invoice/pay separation (gap 3) is **resolved by design** — those axes fold over the
> fulfilment-document chain, not the SO line, and live in finance + the 360 rollup (see **§12.6**).
> §8 and §14 record the resolved state.

---

## 1. The two composition models (they are not the same thing)

"Compose a state machine from sub state machines" splits into two genuinely different
formal constructions. Picking the wrong one is the root cause of most master-detail status
bugs.

### Model A — Product / orthogonal composition (Harel statecharts, UML)

The parent is in **all** of its sub-regions **at once**; the composite state is the
**Cartesian product** of the regions.

```
Composite = R₁ × R₂ × … × Rₙ        (n fixed, regions named & heterogeneous)
```

This is **Harel's AND-decomposition** (Statecharts, 1987), inherited by UML state machines
as *orthogonal regions*. A single event can be **broadcast** to every region. State count
multiplies (`|R₁|·|R₂|·…`), which is exactly why it's only tractable for a small, *fixed*,
*named*, *heterogeneous* set of concurrent aspects.

Good fit:

- A document that is simultaneously `{draft|published} × {locked|unlocked} × {synced|dirty}`.
- A machine that is `{running|stopped} × {heating|cooling|idle}`.

**Why it does *not* fit a sales order over its lines:** the lines play a *uniform role*
(each is "a line progressing toward shipment"), the count is *dynamic* (lines added /
removed at runtime — see `SalesOrder.addLine` / `removeLine`), and the parent does not want
to track the full tuple `(line₁state, line₂state, …)` — it wants a *summary*. Forcing lines
into orthogonal regions gives a product state space of unbounded, runtime-varying arity and
makes "all lines shipped" a conjunction over an unknown number of regions. Process-calculus
parallel composition (CSP/CCS `P ‖ Q`, communicating automata) has the same limitation —
it interleaves a *fixed* roster of named participants.

Note "uniform role" is weaker than "identical state machine": lines for a made-to-order
product and a bought product can run *different* internal FSMs and Model B still applies, as
long as they share a common abstraction onto one progress lattice (§2). Don't read
"homogeneous" anywhere below as "same state set" — the precondition is the shared
abstraction, worked through in **§11**.

### Model B — Aggregation / rollup (homomorphic fold) ← **this is master-detail**

The children are the real state machines. The parent's fulfilment state is **not an
autonomous FSM with its own transition table** — it is a **pure function of the multiset of
child states**:

```
parentState = ρ( ⟦ child.state : child ∈ parent ⟧ )      (⟦…⟧ = multiset)
```

The number of children is dynamic, they play a uniform role, and the parent keeps a
*derived summary*, not the product. `PARTIALLY_SHIPPED` is exactly the output of `ρ`
detecting that the line multiset straddles the shipped boundary. This is the model the rest
of the note develops. The children need not share a state *set* — only a common abstraction
into the summary monoid `M` (§2, §11).

> One system usually needs **both**: Model B for the bottom-up rollup, Model A's broadcast
> for top-down whole-order commands. See §6.

A third formalism, **(coloured) Petri nets**, models Model B natively — *N* lines = *N*
tokens flowing through one shared set of places, "all shipped" = all tokens in the
`shipped` place. It captures dynamic-cardinality concurrency that statecharts can't, but
it's heavier than the fold if all you need is the summary. Worth reaching for only when the
detail lines genuinely synchronise with each other (rare in master-detail; common in
manufacturing routing).

---

## 2. The algebra of the rollup `ρ`

Let:

- `L` = the set of **line** states, with a **progress partial order** `⊑` (how far through
  fulfilment a line is).
- `O` = the set of **order** (parent) states.
- `ρ : Multiset(L) → O` = the rollup.

For `ρ` to be well-behaved (and for the parent status never to depend on row order, insert
order, or how shipments were batched), it must factor as a **fold over a commutative
monoid**:

```
ρ = classify ∘ fold(⊕) ∘ map(g)

   g       : L → M             lift each line into a summary monoid M
   ⊕       : M × M → M         associative + commutative + identity  (a commutative monoid)
   classify: M → O             name the resulting summary as a parent state
```

The commutativity + associativity of `⊕` is the formal statement of *"reordering or
re-batching the lines cannot change the order's status"*. This is the property you most
want to preserve and the one ad-hoc `if` ladders most often break.

**`g` is a *family* of maps, not one map.** The lines need not be drawn from a single state
set `L`. Different line variants (a made-to-order line, a bought line, a stock-pick line)
may run different internal FSMs `L_make`, `L_buy`, …; the fold only requires a per-variant
`g_v : L_v → M` into the *same* monoid `M`, each `g_v` **monotone** (order-preserving) so
the meet/join below stay coherent. The load-bearing precondition for Model B is therefore
**"a shared monotone abstraction onto a common progress lattice exists,"** not "all lines
have the same state set." See §11 for the make-vs-buy case that makes this concrete.

### 2.1 The progress chain for a sales-order line (one axis)

Northwood's `SalesOrder.LineStatus` (lines that are *live* — not cancelled) form an
almost-total **progress chain** *along the shipment axis*:

```
OPEN ⊏ PARTIALLY_RESERVED ⊏ RESERVED ⊏ WAITING_FOR_PRODUCTION? ⊏ READY_TO_SHIP
     ⊏ PARTIALLY_SHIPPED ⊏ SHIPPED
```

`CANCELLED` is **not on the chain** — it is absorbing/neutral and is *filtered out* of the
fold, exactly the way `SalesOrder.recomputeTotals()` skips cancelled lines. Treat it as the
monoid identity from the parent's point of view: a cancelled line contributes nothing to
the order's progress summary.

(The `?` marks `WAITING_FOR_PRODUCTION` / `READY_TO_SHIP`, which are *schema-prep* on the
enum today — accepted by the column, not yet produced by Java.)

This is a chain **per axis**. A line also progresses along an *invoicing* axis and a
*payment* axis that advance independently of shipment (you can invoice before shipping, ship
before invoicing, or split both at different quantities). The full line state is therefore a
*product* of one such chain per axis, not a single chain — see **§12**. This section and
§2.2 develop the shipment axis in isolation; the per-axis fold below applies unchanged to
each axis.

### 2.2 The two summaries you actually need

The whole "partial" question is captured by tracking, over the live lines, the **least**
and **greatest** progress reached:

```
m = ⊓ live-lines   (meet  — the least-advanced live line)
M = ⊔ live-lines   (join  — the most-advanced live line)
```

Then:

```
classify(m, M) =
   CANCELLED            if there are no live lines           (all lines cancelled)
   m                    if m == M                            (every live line agrees: uniform)
   PARTIALLY_<M>        if m ⊏ M and M crosses a "partial" boundary   (mixed: a backorder remains)
   m                    otherwise (mixed but below the partial-emitting boundary)
```

Concretely for shipment: if every live line is `SHIPPED` → order `SHIPPED`; if some are
`SHIPPED` and others aren't → order `PARTIALLY_SHIPPED`; if none have shipped → the order
sits at whatever the meet of the earlier chain says (`IN_FULFILMENT`-ish). This is the
generalisation of the single line in `SalesOrder.recordShipped`:

```java
boolean orderFullyShipped = lines.stream().allMatch(l -> l.lineStatus() == LineStatus.SHIPPED);
this.status = orderFullyShipped ? Status.SHIPPED : Status.PARTIALLY_SHIPPED;
```

`allMatch(SHIPPED)` is precisely `m == M == SHIPPED`. The general fold just makes the
*whole* status derivable this way, not only the shipped/partially-shipped distinction.

### 2.3 Quantity is a second, finer composition (the model recurses)

A line is **itself** a composed state machine — over its *quantity buckets*, not over child
rows:

```
orderedQuantity = reservedQuantity + shippedQuantity + backorderedQuantity + cancelledQuantity
```

`SalesOrderLine` already carries `orderedQuantity`, `reservedQuantity`, `shippedQuantity`,
and the derived `backorderedQuantity()`. The line's *status* is a `classify` over those
buckets:

```java
this.lineStatus = next.compareTo(orderedQuantity) >= 0
    ? LineStatus.SHIPPED            // shipped bucket == ordered  → uniform "all"
    : LineStatus.PARTIALLY_SHIPPED; // shipped bucket  < ordered  → "some"
```

So the structure is **fractal**: `order ⊐ line ⊐ quantity-bucket`, the same
`classify(meet, join)` shape at each level. The order rolls up line *statuses*; each line
rolls up its own *quantities*. Recognising this keeps the two levels from being conflated
(a frequent bug: deriving order status straight from summed quantities, skipping the line
level, which loses per-line backorder identity).

---

## 3. Properties the rollup must hold (and how to test them)

These are the invariants that make `ρ` a *model* rather than an ad-hoc function. Each is a
property test worth writing for any composed status.

| Property | Statement | Why it matters | Test shape |
|---|---|---|---|
| **Order-insensitivity** | `ρ(shuffle(lines)) == ρ(lines)` | status can't depend on row/insert/shipment-batch order | shuffle lines, assert equal status |
| **Idempotent saturation** | once all-`SHIPPED`, adding more shipped detail keeps it `SHIPPED` | re-delivered events / replays don't flip a terminal state | apply same shipment twice |
| **Monotonicity (forward flow)** | absent cancel/amend, `M` never decreases | the order can't appear to regress through fulfilment | sequence of shipments, assert `⊑`-monotone |
| **Cancelled-neutrality** | a `CANCELLED` line doesn't change `ρ` of the rest | matches `recomputeTotals()` skipping cancelled | cancel a line, assert status unchanged |
| **Empty-live ⇒ terminal** | no live lines ⇒ `CANCELLED` (or `COMPLETED`) | the "removed the last line" edge (`removeLine` guards this) | cancel all lines |
| **Derived-not-stored agreement** | stored header status == `ρ(current lines)` | the total can never silently diverge from the facts | invariant check after every mutation |

The last row is the Pacioli point from `CLAUDE.md` (*deltas get aggregates, totals get
projections*) restated for state: **a composed status is a total; storing it as an
independently-writable field re-introduces the divergence double-entry was invented to
prevent.** See §8.

---

## 4. The two coupling directions

A composed master-detail record has **two** distinct flows, and they want the two different
models from §1. Conflating them is the second-most-common source of bugs.

| Direction | Trigger → effect | Model | In the code |
|---|---|---|---|
| **Bottom-up (rollup)** | a line advances → recompute parent status | Model B fold (§2) | `recordShipped` recomputing `status` from line statuses |
| **Top-down (broadcast)** | a command on the parent → fan out to lines | Model A broadcast (§1) | `cancel()` / `removeLine()` propagating to lines |

Two rules fall out of keeping them separate:

1. **Precedence.** A top-down command can *override* the fold (cancelling an order forces
   `CANCELLED` regardless of line states), but the fold must never *silently undo* a
   top-down terminal state. Model this as: terminal parent states (`CANCELLED`, `REJECTED`,
   `COMPLETED`) are absorbing and the fold is only consulted while the parent is in its
   *derived region* (the fulfilment phase). See §5.
2. **Fan-out is a command, not a fold.** "Cancel the order ⇒ release every non-shipped
   line's stock" is broadcast (Model A) — it issues per-line intent and emits per-line
   events (so inventory can release). It is *not* expressible as a `classify(meet, join)`.

---

## 5. The header is two-tier: an autonomous FSM *then* a derived region

A subtlety the raw fold misses: `SalesOrder.Status` is **not** purely a function of the
lines. Its early states are header-level lifecycle that the lines say nothing about:

```
DRAFT? → SUBMITTED → CONFIRMED? → IN_FULFILMENT → [ derived region ] → COMPLETED
                                       │
                                       └── PARTIALLY_SHIPPED / SHIPPED   ← ρ(lines)
   cancel ⇒ CANCELLED   (absorbing, any pre-ship state)
   reject ⇒ REJECTED    (absorbing)
```

So the parent is a **layered / hierarchical** machine:

- An **autonomous header FSM** owns the front (`SUBMITTED → IN_FULFILMENT`) and the
  absorbing terminals (`CANCELLED`, `REJECTED`, `COMPLETED`). These transitions are driven
  by header-level commands and the Saga, not by line states.
- A **derived sub-region** (active only during fulfilment) where the status *is* `ρ(lines)`
  — this is where `IN_FULFILMENT` / `PARTIALLY_SHIPPED` / `SHIPPED` come from.

The clean mental model: the header is a Model-A composition of **two regions** — a
*lifecycle* region (autonomous) and a *fulfilment-progress* region (derived from lines via
Model B) — with a precedence rule that the lifecycle region's terminal states win. This
two-tier shape is why you can't just write `headerStatus = ρ(lines)` and be done; the fold
governs one region of a larger machine.

This also cleanly separates from the **Saga** state machine: `SalesOrderFulfilmentSaga`
state is *process progress* (a third machine), deliberately distinct from both the header
lifecycle and the line rollup — see `docs/sagas.md` → *Aggregate vs Saga*. Three machines,
three owners: line FSM (per line), header FSM+fold (the aggregate), saga FSM (the process).

---

## 6. ERP catalogue — the same shape, eight places

The composed-state pattern recurs across the domain. Each is a `classify(meet, join)` over
uniform-role detail lines plus a two-tier header. Cataloguing them is the point of
formalising — they should share one mechanism, not eight bespoke `if` ladders.

| Parent (master) | Children (detail) | Line progress chain (sketch) | Parent "partial" state |
|---|---|---|---|
| **Sales order** | order lines | open → reserved → shipped | `PARTIALLY_SHIPPED` |
| **Purchase order** | PO lines (goods receipt) | open → partially_received → received | `partially_received` |
| **Customer invoice** | — (payment allocations against it) | unpaid → partially_paid → paid | `partially_paid` (a *total*: `paid_amount`) |
| **Shipment / picking** | shipment lines | pending → picked → packed → shipped | `partially_picked` |
| **Work order** | material lines (issue) + operations (routing) | not_issued → issued; pending → done | `in_progress` |
| **Replenishment / MRP run** | requisition lines | proposed → ordered → received | `partially_fulfilled` |
| **Receipt (GRN)** | receipt lines | pending → inspected → put_away | `partially_put_away` |
| **Customer / supplier** | open orders (rollup for credit/standing) | n/a — a *count/sum* rollup | derived flags |

Two cautions visible in the table:

- Some "parents" roll up **totals not statuses** (invoice `paid_amount`, customer
  exposure). Those are projections in Northwood (`customer_invoice_header.paid_amount`,
  `stock_balance`) — same fold algebra, but the `classify` output is a number-plus-flag, and
  per *deltas get aggregates, totals get projections* they live on the read model, fed by
  events, **never** as an independently-writable aggregate field.
- The work-order case mixes a chain (material issue) with a **routing graph** (operations
  with precedence). Routing is the one place Model A / Petri-net composition genuinely earns
  its keep — operations synchronise (op 20 can't start until op 10 finishes), which a
  commutative fold cannot express.

---

## 7. Implementation shapes (three, by where the parent state lives)

### 7.1 Derived-on-read (recommended default)

Don't store the composed status as truth at all. Store line states; compute `ρ(lines)`
on demand and in the projection. Header `status` column becomes a **cache** of `ρ`, written
only inside the aggregate immediately after a line mutation, with an invariant check that
it equals `ρ(lines)`.

- Pros: cannot diverge; matches *totals are projections*.
- Cons: the header column is denormalised cache — needs the invariant test (§3, last row).

### 7.2 Aggregate-recomputes-on-mutation (what `SalesOrder` half-does today)

The aggregate recomputes `status` whenever a line changes, inside the same transaction, and
emits the header-level event. This is §7.1 with the cache write made explicit and
event-sourced. The fold lives in **one** private method on the aggregate
(`recomputeStatus()`), called from every line mutator — the analogue of the existing
`recomputeTotals()`.

### 7.3 Projection-side rollup (for cross-service / read-model parents)

When the "parent" lives in another service (reporting's `sales_order_360`, a customer
exposure view), the rollup runs in a `*Projection` that consumes the per-line delta events
and re-derives the summary. Pure Model B, no aggregate. This is the right home for the
*total*-shaped rollups in §6.

The dividing line is the existing rule: **does the parent emit its own delta with identity
and downstream consumers?** Yes → aggregate (7.2). No → projection (7.3).

---

## 8. Where the model meets the code today

The codebase implements the **ship-axis fold** of this model (§2.29 Slice A, 2026-06-10);
the multi-axis invoice/pay separation is realized at the order level rather than on the SO
line, for the reason worked out in **§12.6** (full accounting + severity in **§14**):

- ✅ Lines are the authoritative state machines (`SalesOrderLine.lineStatus`, mutated only
  by the aggregate via `markReserved` / `recordShipment` / `cancelLine`). `markReserved` is
  now wired (`StockReservedHandler` → `recordReservation`), so the line carries the
  `RESERVED` / `PARTIALLY_RESERVED` band in production, not just the terminal ship states.
- ✅ Header status **is** the general fold: `SalesOrder.recomputeStatus()` re-derives
  `SUBMITTED → IN_FULFILMENT → PARTIALLY_SHIPPED → SHIPPED` from the live-line multiset via
  `classify(meet, join)` after *every* mutation (place / amend / reserve / ship). The old
  `allMatch(SHIPPED)` is now this fold's `meet == SHIPPED`.
- ✅ The aggregate is the **sole writer** of `status`: the derived region comes from the
  fold; the absorbing terminals are guarded transitions (`cancel` / `reject` / `complete`).
  The former blind `SalesOrderHeaderStatusProjection.markStatus(...)` is **retired**.
- ✅ Quantity-bucket sub-composition exists (`shippedQuantity` vs `orderedQuantity` →
  line `SHIPPED` vs `PARTIALLY_SHIPPED`).
- ✅ Cancelled-neutrality is honoured for both *totals* (`recomputeTotals` skips cancelled)
  and *status* (the fold filters cancelled lines), and is property-tested (§3 shapes in
  `SalesOrderTest.StatusFold`).
- 🔵 **Invoice/pay are not on the SO line — by design (§12.6).** They fold over the
  fulfilment-document chain (which cross-cuts the lines), not the SO lines, so they live in
  `finance.CustomerInvoice` / `Payment` + the `reporting.sales_order_360_view` rollup. The
  cross-axis `COMPLETED` meet is the saga sequencing those documents; `complete()` asserts
  only the one leg the aggregate owns the quantities for (`ordered = shipped`).
- ⚠️ Several `LineStatus` values (`WAITING_FOR_PRODUCTION`, `READY_TO_SHIP`) are
  *schema-prep* — the make/buy part of the chain in §2.1 rides the to-order extension
  (dev-todo §2.43), still future.

**The load-bearing tie-in:** a composed status is a *total over the line deltas*. Promoting
it to an independently-`UPDATE`-able header field is the same mistake as promoting
`stock_balance` to an aggregate — it lets the summary diverge from the facts. So the
composed-state model is not a new principle; it's *deltas get aggregates, totals get
projections* applied to **state** instead of **money/quantity**. The lines emit the deltas;
the header status is the running fold of them.

---

## 9. Anti-patterns (code-review smells)

- **Independently-writable parent status.** A `setStatus` / direct `UPDATE
  sales_order_header SET status` outside the fold. The status must only ever be (re)written
  as `ρ(lines)`.
- **Left-fold with hidden order dependence.** Deriving status by walking lines and
  mutating an accumulator whose result depends on iteration order — breaks
  order-insensitivity.
- **Skipping the line tier.** Computing header status directly from summed quantities,
  bypassing line statuses — loses per-line backorder identity (which line is short).
- **Fold undoing a top-down terminal.** Recomputing `ρ` while the order is `CANCELLED` and
  flipping it back. Terminals are absorbing (§4, §5).
- **Orthogonal-region modelling of uniform-role lines.** Trying to express *N* lines as *N*
  statechart regions — the §1 Model-A misfit.
- **Variant-specific status leaking into the fold.** Branching the rollup on product type
  (`if (line.isManufactured()) …`) instead of mapping each variant onto the common progress
  lattice first — re-introduces the heterogeneity the abstraction was meant to erase (§11).
- **One bespoke `if` ladder per master-detail type.** Eight copies of the rollup (§6)
  instead of one shared `classify(meet, join)` mechanism.

---

## 10. Open questions for discussion

1. **Generalise the fold?** Should we extract a reusable `compose(lines, chain)` →
   parent-status mechanism (shared-kernel), or keep per-aggregate `recomputeStatus()`
   methods? Trade-off: one mechanism vs the heterogeneity of each chain's `classify`.
2. **Model the derived-region boundary explicitly?** Right now §5's two tiers are implicit.
   Is it worth a small `enum`-of-regions or a guard so the fold provably runs only during
   fulfilment?
3. **Where does `COMPLETED` come from?** It's a terminal past `SHIPPED` — is it a fold
   output (all lines shipped *and* invoiced *and* paid → a cross-aggregate rollup) or a
   header-lifecycle transition driven by the Saga? Cross-aggregate folds are a different
   (harder) animal.
4. **Routing graphs.** Work-order operations need Model A / Petri-net synchronisation, not a
   commutative fold. Do we want a second, explicitly-different mechanism for the
   precedence-graph cases, or keep those bespoke?
5. **Property-test harness.** Worth a generic property-test (shuffle / replay / cancel-
   neutrality from §3) parameterised over each composed status?
6. **Quantity vs status as the source of truth.** §2.3 has both. Should line status always
   be *derived* from quantity buckets (never set directly), making quantity the single
   authority and status a pure `classify`?

---

## 11. Heterogeneous line variants (make vs buy) — the fold survives

A natural objection: *a product is either manufactured or purchased, so two SO lines can run
different state machines — doesn't that break the homomorphic fold?* It does not. It
sharpens what the fold requires, and the resolution is one of the more useful results in
this note.

### 11.1 The fold never required identical state sets

Model B factors as `classify ∘ fold(⊕) ∘ map(g)` (§2). The fold combines values **in the
monoid `M`**; it is indifferent to the *domain* each line is mapped *from*. `g` is a family
`{ g_v : L_v → M }` — one map per variant — and the only conditions are:

1. all `g_v` land in the **same** progress lattice `M`, and
2. each `g_v` is **monotone** (a line advancing in its own FSM never goes backwards in `M`).

A made-to-order line and a bought line run different internal chains:

```
L_make :  open → planned → in_production → produced → ready_to_ship → shipped
L_buy  :  open → reserved →                            ready_to_ship → shipped
L_stock:  open → reserved →                                            shipped
```

…but all three **coarsen** onto the order's progress lattice:

```
M :  NOT_STARTED ⊏ IN_PROGRESS ⊏ READY ⊏ SHIPPED
```

The order does not care *how* a line becomes shippable (produced vs received vs picked) —
only *that* it does. `WAITING_FOR_PRODUCTION` (schema-prep in `SalesOrder.LineStatus`) is
simply a refinement of the `IN_PROGRESS` band that only make lines visit. The fold runs on
`M`, so heterogeneous `L_v` are erased before the meet/join ever sees them. **The fold is
intact; the precondition is "a shared monotone abstraction exists," which it does.**

### 11.2 Two ways to carry the variants

| Approach | Shape | Trade-off |
|---|---|---|
| **(A) Superset enum** | one `LineStatus` chain holding every variant's states; buy lines never reach `WAITING_FOR_PRODUCTION` | fold trivially valid (one chain); but illegal states are *representable* (a buy line is type-able as `WAITING_FOR_PRODUCTION`) |
| **(B) Indexed family + φ** | distinct `MakeLineState` / `BuyLineState` types, each with a monotone `φ` into a shared `ProgressBand` | no illegal states; costs the abstraction-map machinery |

Northwood uses **(A)** — consistent with *aggregate enumerated fields = one nested enum*.
The thing to know: (A) relies on "unreachable per variant," not "nonexistent per variant."
If illegal-state-representability ever bites, (B) is the principled upgrade, and the fold
doesn't change — only `g` moves from "identity on one enum" to an explicit `φ`.

### 11.3 When it genuinely breaks — and the stratified resolution

Model B fails **only** if no common coarsening exists — i.e. the parent must *report*
variant-specific milestones as its status (*"make-lines: 2/3 produced; buy-lines: 1/2
received"*), not just "partially shipped." That can't collapse to one scalar. But the fix is
not to abandon the fold — it is to **stratify the composition by level**:

```
            Model A  —  small, FIXED product across variant KINDS
            ┌───────────────────┬───────────────────┐
       make-group            buy-group           stock-group
       summary               summary             summary
            ▲                    ▲                   ▲
       Model B fold         Model B fold        Model B fold
       (dynamic N lines)    (dynamic N lines)   (dynamic N lines)
```

- **Within a variant group** — uniform lines, dynamic count → Model B fold (its sweet spot).
- **Across variant kinds** — the roster of kinds is *fixed and small* (make / buy / stock,
  2–3) → Model A product (its sweet spot).

This dissolves the apparent tension: the count of *lines* is dynamic (wants Model B) while
the count of *variant kinds* is fixed (wants Model A) — they are not competing, they sit at
**different levels of the hierarchy**. If the order only needs "all / some shipped" (the
common case), the top product collapses to a single fold and Model A never appears. You only
pay for the product when the order genuinely reports per-kind progress.

### 11.4 In Northwood today this is moot — by design

For the SO rollup *right now* the question doesn't even arise: under **make-to-stock**, an
SO line fulfils **identically** whether the product is made or bought — reserve from stock →
ship. The make-vs-buy decision lives upstream in **inventory / replenishment**, a different
aggregate (`project_make_to_stock_rationale`, the §2.35 mfg↔purchasing decoupling), and the
SO line never sees it. So `L_make = L_buy` at the SO-line level and every line runs the one
chain.

Heterogeneous SO lines only appear with the opt-in **to-order extension** (`to_order`
products, dev-todo §2.43), where a line waits on a work order or a purchase. When that lands
it arrives as the §2.1 chain's `WAITING_FOR_PRODUCTION` / `READY_TO_SHIP` bands — i.e.
approach (A), the common coarsening — and §11.1 guarantees the fold keeps working. The
stratified §11.3 product is only needed if to-order also requires per-kind status *reporting*
on the order header, which it currently does not.

---

## 12. Multi-axis fulfilment (independent ship / invoice / pay progress)

A second objection that looks fatal but isn't: *if we ship and invoice lines partially and
at different quantities — line 1 = 10×A, line 2 = 10×B, one shipment+invoice of 2×A + 4×B —
does the fold survive?* It does. What it retires is the §2.1 simplification that a line sits
on a **single** chain. The line was always on a **product of chains, one per axis**; partial
multi-line ship/invoice is just what forces you to see it.

### 12.1 A line state is a product of monotone quantity folds

Shipping, invoicing, and payment are **independent progress axes**. Neither
`shipped ⊑ invoiced` nor its reverse holds in general — you can invoice before shipping
(prepayment), ship before invoicing, or split each at unrelated quantities. So:

```
line.state  =  (shipProgress, invoiceProgress, payProgress, …)   ∈  M_ship × M_invoice × M_pay
```

Crucially, **a product of lattices is still a lattice** (componentwise meet/join), so `M` is
still a valid commutative monoid and the §2 algebra is untouched. Each axis is itself a
quantity-accumulation fold over deltas:

```
line.shippedQuantity  = Σ (shipment deltas touching this line)
line.invoicedQuantity = Σ (invoice  deltas touching this line)
```

That `Σ` is a fold over **addition** — the cleanest commutative monoid there is. So
partial, arbitrarily-split fulfilment doesn't strain Model B; it instantiates it with the
most well-behaved `⊕` available. §2.3's quantity-bucket level is now visibly a `Σ`-fold of
deltas, one per axis.

### 12.2 The order rollup becomes a product of per-axis folds

The fold runs **componentwise** — one independent Model-B rollup per axis:

```
order.shipStatus    = ρ_ship   ( live lines' shipped fractions )
order.invoiceStatus = ρ_invoice( live lines' invoiced fractions )
order.payStatus     = ρ_pay    ( … )
```

So the parent state is a **product of per-axis rollups** — Model A (orthogonal regions) on
top of Model B folds. This is the **same stratification as §11.3, along a different axis**:
§11 took the product across variant *kinds* (make/buy); §12 takes it across progress
*dimensions* (ship/invoice/pay). The codebase already commits to exactly this — `SalesOrder`
keeps **one** `status` (the shipment chain) and its own Javadoc rules that *"cross-cutting
flags (stock, mfg, shipment, invoice) belong on the read model fed by saga events, not on
the aggregate itself."* That is "one status = ship axis; the other axes are orthogonal
regions on the projection."

### 12.3 The cross-line bundling is a non-issue — by construction

"2×A + 4×B in one shipment" never touches the SO-line rollup, because each line accumulates
**only its own** delta (§12.1). That 2×A and 4×B shared a shipment is a fact about the
`Shipment` aggregate, not about either SO line; each line is blind to the bundling. So
order-insensitivity holds against replays, re-batched shipments, and out-of-order delivery —
all land at the same quantities, hence the same status. The path-independence test:
**is the final status a function of the final quantity vector, regardless of arrival
order?** Yes — `classify(shippedQty, orderedQty)` doesn't care how it got there. (The
*journal entries* posted along the way are path-dependent, but those are deltas/aggregates,
not the status total — *deltas get aggregates, totals get projections* again.)

### 12.4 The four real boundaries

| # | Boundary | Consequence |
|---|---|---|
| 1 | **Don't collapse axes into one total order.** A chain `open → shipped → invoiced → paid` is *wrong* — invoice can precede ship. | It's a lattice **product**, not a chain. Forcing one chain is the classic ERP status-modelling bug this rules out. |
| 2 | **Monotonicity breaks under returns / credit notes.** A return *decreases* `shippedQty`; a credit note decreases invoiced. | The "progress lattice" stops modelling "furthest reached"; meet/join no longer mean what §2.2 says. Model B is sound **only over the forward, monotone flow**. Reversals need signed deltas + a *net* projection, not a status chain. Currently out of scope (`project_credit_notes_low_priority`). |
| 3 | **"Invoice ≤ shipped" is a synchronisation, not free orthogonality.** | If a rule couples the axes, the regions aren't independent — it's a *constrained* product: Model A regions **with inter-region guards** (a statechart guard on the invoice region reading the ship region). Expressible, but a coupling point to name, not free concurrency. |
| 4 | **The invoice/pay axes aren't on the SO aggregate.** Invoicing lives in `finance.CustomerInvoice` (own aggregate, own composed state, `customer_invoice_header.paid_amount` a projection-total per §6). | "Is this order fully invoiced/paid?" is a **cross-aggregate, cross-service** rollup — a fold whose inputs arrive via events from another bounded context. Strictly harder than the within-aggregate line fold; it's the machinery behind open question §10.3 (where `COMPLETED` comes from: *all lines shipped ∧ invoiced ∧ paid*). |

### 12.5 Net

Partial, arbitrarily-split, multi-line ship + invoice **does not break Model B.** It retires
the single-chain simplification: a line is a **product of monotone quantity-accumulation
folds** (one per axis), and the order is a **product of per-axis folds** (Model A over Model
B — the §11 stratification on a new axis). The fold itself only gives out at **reversals**
(returns/credit notes break per-axis monotonicity, boundary #2), and the only modelling trap
is **forcing the orthogonal axes into one chain** (boundary #1).

### 12.6 The fulfilment-event triple — each axis folds over a *different* detail set

§12.2 says the order is a product of per-axis folds. The sharp question §12.1–12.5 left
implicit is: **what does each axis fold *over*?** The answer is not "the SO lines" for all
three axes — and getting this right is what tells you where each axis's state belongs.

**The order is a quantity matrix `M`.** For an order of `line-1: 4×A, line-2: 2×B`,
`M = { A:4, B:2 }`. There are **two orthogonal partitions** of `M`:

- **By line (product)** — the SO lines: `{A:4} | {B:2}`. Fixed, the order's own structure.
- **By fulfilment event** — the ship/invoice/pay documents: *any* set of sub-vectors `M_e`
  with `Σ M_e = M`. One event `{4A+2B}`; or two `{3A+1B}+{1A+1B}`; or six singletons; etc.
  This partition **cross-cuts the lines** — a single shipment `{3A+1B}` touches both lines.

A **fulfilment event** `e` is a *triple* `(shipment_e, invoice_e, payment_e)`, all covering
the same `M_e`: a shipment of `M_e` begets one invoice for `M_e` (`createFromShippedOrder`
raises an invoice per `SalesOrderShipped`), settled by one payment. The order is fulfilled by
a **dynamic set of these triples** (uniform role, dynamic count → a Model-B fold), each
triple itself a little `ship → invoice → pay` chain. (Prepayment / deposit just *reorder* the
triple — invoice/pay lead the shipment — but it is the same unit; the saga owns the ordering.)

**The conservation invariant (Pacioli, again).** The two partitions are decompositions of the
*same* total, so at completion they reconcile on every axis:

```
Σ shipments  =  Σ invoices  =  Σ payments  =  Σ SO lines  =  M
ordered      =  shipped     =  invoiced    =  paid
```

This is the order-to-cash form of the double-entry identity. A reconciled total is a
**projection**; the per-document line deltas that sum into it are **aggregates**. So each leg
of the reconciliation lives where that principle puts it:

| Leg | Reconciled in | Grain | Why there |
|---|---|---|---|
| `ordered = shipped` | the **SO aggregate** (`complete()` asserts it; the §13.3 fold) | quantity, per SO line | shipment lines carry `salesOrderLineId + qty` — line-aligned, so the SO line is a valid accumulator |
| `invoiced = ordered` | the **360 projection** (`invoiced_amount` vs `total_amount`) | value, order-level | invoice docs cross-cut lines; deposit/balance invoices are value-only (no `salesOrderLineId`) |
| `paid = ordered` | the **360 projection** (`paid_amount` vs `total_amount`) | value, order-level | payments settle *invoices*, never SO lines |

**Consequence — no invoice axis on the SO line.** Putting an `invoicedQuantity` bucket on the
SO line would force the invoice axis onto the line grain it does not fit (it folds over the
document chain, not the lines) and could not even be populated for value-based deposit/balance
invoices. The invoice and pay axes belong to `finance.CustomerInvoice` / `Payment` and the
`sales_order_360_view` rollup; the **saga** is the cross-aggregate meet that sequences the
triples. Because an invoice sits *structurally between* ship and pay — you cannot pay an
amount that was never invoiced, and an invoice is raised per shipment — `shipped ∧ paid`
already implies `invoiced`, which is why the 360's `completed = shipped ∧ paid_amount ≥ total`
rule is sound with no separate invoice gate, and why the aggregate's `complete()` need only
assert the one leg it owns the quantities for (`ordered = shipped`).

---

## 13. The concrete `M` and `classify` (worked examples)

The payoff for §§2/11/12: the actual state set `M` and the rollup `classify`, written out
for the **aspirational full chain** (including the schema-prep `waiting_for_production` /
`ready_to_ship` states and the to-order make/buy variants — *not* limited to what Java emits
today), followed by three worked examples covering every case in this note.

### 13.1 `M` — the state set for any product line

A line's full state is a **product of one progress lattice per axis**:

```
M  =  M_ship  ×  M_invoice  ×  M_pay
```

**Shipment axis** — one coarse **band** (what the order fold sees) onto which the three
variant chains coarsen via monotone `g_v` (§11):

| Band `M_ship` (fold sees this) | stock line `g_stock` | make-to-order `g_make` | buy-to-order `g_buy` |
|---|---|---|---|
| `NOT_STARTED` | `open` | `open` | `open` |
| `IN_PROGRESS` | `partially_reserved` | `waiting_for_production` | `awaiting_receipt` |
| `READY` | `reserved` | `ready_to_ship` | `ready_to_ship` |
| `PARTIALLY_SHIPPED` | `partially_shipped` | `partially_shipped` | `partially_shipped` |
| `SHIPPED` | `shipped` | `shipped` | `shipped` |

Total order `NOT_STARTED ⊏ IN_PROGRESS ⊏ READY ⊏ PARTIALLY_SHIPPED ⊏ SHIPPED`; `cancelled`
is **off the chain** (neutral — filtered from the fold). A stock line in `reserved`, a make
line in `ready_to_ship`, and a buy line in `ready_to_ship` all coarsen to `READY`, so the
order fold treats them identically and never branches on product type.

**Invoice + payment axes:**

```
M_invoice :  NOT_INVOICED  ⊏  PARTIALLY_INVOICED  ⊏  INVOICED
M_pay     :  NOT_PAID      ⊏  PARTIALLY_PAID      ⊏  PAID      (on finance.CustomerInvoice; projected back — §12.4 #4)
```

Full per-line lattice = `5 × 3 × 3` points (cancel aside); coupling rules
(e.g. `invoiced ≤ shipped`) make most unreachable.

### 13.2 Line-level `classify` (quantity buckets → band) — §2.3

```
band_ship(line) =
    SHIPPED            if shippedQty == orderedQty
    PARTIALLY_SHIPPED  if 0 < shippedQty < orderedQty
    READY              if shippedQty == 0  ∧  (reserved|produced)Qty == orderedQty
    IN_PROGRESS        if partial reservation / in production
    NOT_STARTED        otherwise
    CANCELLED          if line soft-removed        (neutral)

band_invoice(line) =
    INVOICED           if invoicedQty == orderedQty
    PARTIALLY_INVOICED if 0 < invoicedQty < orderedQty
    NOT_INVOICED       otherwise
```

### 13.3 Order-level `classify` (line multiset → `SalesOrder.Status`)

The header carries **one** status column = the **ship axis** (other axes are orthogonal
projection flags). Two-tier (§5): lifecycle/terminal overrides wrap a derived region. Over
the **live** lines (cancel filtered), per axis compute `meet = ⊓ bands`, `join = ⊔ bands`:

```
classify(order) =
    CANCELLED          if cancel-command issued   OR   no live lines remain
    REJECTED           if reject-command issued
    COMPLETED          if meet_ship    == SHIPPED            // every line fully shipped …
                          ∧ meet_invoice == INVOICED         // … invoiced …
                          ∧ meet_pay     == PAID              // … and paid   (cross-axis join)
    SHIPPED            if meet_ship == SHIPPED                // all shipped, not yet fully invoiced/paid
    PARTIALLY_SHIPPED  if join_ship ⊒ PARTIALLY_SHIPPED       // ≥1 unit shipped anywhere, not all done
    IN_FULFILMENT      if join_ship ⊒ IN_PROGRESS             // work underway, nothing shipped yet
    SUBMITTED          otherwise                              // every live line still NOT_STARTED
```

`meet_ship == SHIPPED` ⟺ "*all* live lines fully shipped"; `join_ship ⊒ PARTIALLY_SHIPPED`
⟺ "*at least one* line shipped ≥1 unit." Both commutative ⇒ order-insensitive.

### 13.4 Example A — homogeneous stock lines (base case, §2)

SO: line1 = 10×A (stock), line2 = 5×B (stock).

| t | line1 band | line2 band | `meet_ship` / `join_ship` | header `status` |
|---|---|---|---|---|
| place | NOT_STARTED | NOT_STARTED | NOT_STARTED / NOT_STARTED | **SUBMITTED** |
| both reserved | READY | READY | READY / READY | **IN_FULFILMENT** |
| ship line1 (10A) | SHIPPED | READY | READY / SHIPPED | **PARTIALLY_SHIPPED** |
| ship line2 (5B) | SHIPPED | SHIPPED | SHIPPED / SHIPPED | **SHIPPED** |
| invoiced + paid | — | — | (+ invoice=INVOICED, pay=PAID) | **COMPLETED** |

### 13.5 Example B — make vs buy heterogeneity (§11)

SO: line1 = 10×A (**make**-to-order), line2 = 4×B (**buy**-to-order). Different concrete
states, but the fold only sees the band:

| t | line1 (make) concrete → band | line2 (buy) concrete → band | `meet`/`join` | header |
|---|---|---|---|---|
| place | `open` → NOT_STARTED | `open` → NOT_STARTED | NS / NS | **SUBMITTED** |
| both started | `waiting_for_production` → IN_PROGRESS | `awaiting_receipt` → IN_PROGRESS | IP / IP | **IN_FULFILMENT** |
| line1 produced | `ready_to_ship` → READY | `awaiting_receipt` → IN_PROGRESS | IP / READY | **IN_FULFILMENT** |
| ship line1 | `shipped` → SHIPPED | `ready_to_ship` → READY | READY / SHIPPED | **PARTIALLY_SHIPPED** |

At "both started," `g_make(waiting_for_production) = g_buy(awaiting_receipt) = IN_PROGRESS` —
heterogeneity is **erased before the fold**, so `classify` is identical to the homogeneous
case.

**Stratified per-kind reporting (§11.3):** if the header must report per-kind rather than
one scalar, partition `{line1}` make-group / `{line2}` buy-group, fold each →
`(make: SHIPPED, buy: READY)`, present the **pair** (Model A product over two Model B folds).
Pay for the product only when you actually report per-kind.

### 13.6 Example C — multi-axis partial ship + invoice (§12)

SO: line1 = 10×A, line2 = 10×B (stock). Shipment of **2×A + 4×B**; a separate invoice of
**5×A + 0×B** (deliberately *not* aligned with the shipment, to show axis independence):

| line | ordered | shipped → `band_ship` | invoiced → `band_invoice` |
|---|---|---|---|
| line1 (A) | 10 | 2 → PARTIALLY_SHIPPED | 5 → PARTIALLY_INVOICED |
| line2 (B) | 10 | 4 → PARTIALLY_SHIPPED | 0 → NOT_INVOICED |

Two **independent** componentwise folds:

```
ship axis :   meet = PARTIALLY_SHIPPED,  join = PARTIALLY_SHIPPED   →  header.status     = PARTIALLY_SHIPPED
invoice axis: meet = NOT_INVOICED,       join = PARTIALLY_INVOICED  →  header.invoiceFlag = PARTIALLY_INVOICED
```

One column can't carry both — exactly why they're orthogonal regions, not one chain
(§12.4 #1).

- **Order-insensitivity (§12.3):** had the goods arrived as two shipments (`2×A` then `4×B`,
  reversed, or bundled), each line's `shippedQty` is the same `Σ` ⇒ identical bands and
  header. The cross-line bundling is invisible to the fold.
- **Reversal boundary (§12.4 #2) — where the fold breaks:** a **return of 1×A** sends
  `shippedQty(A): 2 → 1`. The band stays `PARTIALLY_SHIPPED`, but the axis moved
  *backwards* — `join_ship` no longer means "furthest reached" and a replay of the original
  ship event would raise it again. Monotonicity violated ⇒ meet/join stop being a sound
  progress model. Needs signed deltas + a *net* projection, not a status lattice
  (out of scope — `project_credit_notes_low_priority`).
- **COMPLETED (cross-axis, cross-aggregate, §12.4 #4):** reached only when
  `meet_ship == SHIPPED ∧ meet_invoice == INVOICED ∧ meet_pay == PAID`, and `meet_pay` is
  fed from `finance.CustomerInvoice` via events — this join straddles two bounded contexts
  (the §10.3 open question).

---

## 14. Gap analysis — model vs. codebase (resolved by §2.29, 2026-06-10)

§8 sketched where the model meets the code; this is the full accounting. The gaps below were
the *pre-§2.29* state; §2.29 (Slices A + B) closed gaps 1 & 2 and resolved gap 3 by design.
The **headline before**: the composed-state machine was fragmented across three writers where
the model has one. **After:** the aggregate is the single writer of the ship-axis fold; the
invoice/pay axes are document-chain folds owned by finance + the 360 (§12.6).

| Who writes header status | Mechanism | States |
|---|---|---|
| **The aggregate** (`SalesOrder`) — *now the sole writer* | `recomputeStatus()` fold (derived region) + guarded `cancel` / `reject` / `complete` (terminals) | all of `SUBMITTED` / `IN_FULFILMENT` / `PARTIALLY_SHIPPED` / `SHIPPED` / `COMPLETED` / `CANCELLED` / `REJECTED` |
| ~~A blind projection (`markStatus`)~~ | **retired** — `SalesOrderHeaderStatusProjection` deleted | — |
| **The Saga** (`SalesOrderFulfilmentSaga`) | still owns *process* progress (reservation / replenishment / per-triple sequencing) — a distinct machine (§5), feeds the aggregate via guarded transitions | saga states, not header status |

### 14.1 Gap by model pillar — resolution

| Model pillar (ideal) | Resolution (§2.29) | Status |
|---|---|---|
| **Header status = `classify(lines)`** single fold (§13.3) | `SalesOrder.recomputeStatus()` re-derives the ship-axis status after every mutation | ✅ closed (Slice A) |
| **Single writer, status derived not stored** (§9) | blind `markStatus` retired; aggregate is sole writer (fold + guarded terminals) | ✅ closed (Slice A) |
| **Line is the authoritative state machine** (§8) | `markReserved` wired from `StockReservedHandler` → `recordReservation`; line carries the `RESERVED`/`PARTIALLY_RESERVED` band, fold derives `IN_FULFILMENT` | ✅ closed (Slice A) |
| **Multi-axis product** `M_ship × M_invoice × M_pay` (§12) | ship axis folds over SO lines (aggregate); invoice/pay fold over the fulfilment-document chain (finance + 360), **not** the SO line — the per-event-triple model (§12.6). `COMPLETED` is the saga's cross-aggregate meet, with `complete()` asserting the `ordered = shipped` leg | ✅ resolved by design (Slice B) |
| **Variant coarsening** make/buy → common band (§11) | unchanged — rides the to-order extension (dev-todo §2.43) | 🔵 deferred |
| **Quantity-bucket `Σ`-folds** (§2.3) | `shippedQty` + `reservedQty` now both accumulate; `manufacturingRequiredQty` still unwired (MTS) | ✅ mostly closed |
| **Cancelled-neutrality in the fold** | the status fold filters cancelled lines (property-tested) | ✅ closed |

### 14.2 The three gaps — how they closed

1. **Single `classify` + single writer (was High).** `SalesOrder.recomputeStatus()` is the one
   `classify(meet, join)` over the live lines, called after every mutation; it is the *sole*
   writer of the derived region, and the absorbing terminals are guarded transitions
   (`cancel` / `reject` / `complete`). The blind `markStatus` projection is **deleted**, so the
   §9 *independently-writable status* anti-pattern is gone — there is no second writer to
   desync. ✅ **Closed (Slice A).**

2. **In-progress band on the line (was Medium).** `markReserved` is wired from
   `StockReservedHandler` via `SalesOrderService.recordReservation`, so the line authoritatively
   carries `RESERVED` / `PARTIALLY_RESERVED` and the fold derives `IN_FULFILMENT` from it
   (Option 2, not "document the split"). `reservedQuantity` is no longer dead. The Saga still
   owns the states the line cannot represent (awaiting-replenishment, ready-to-ship) — those are
   *process* state by design (§5). ✅ **Closed (Slice A).**

3. **Multi-axis — resolved by recognising the axes fold over different detail sets (was
   Medium).** The fix is *not* an `invoicedQuantity` bucket on the SO line. Per §12.6, the order
   is fulfilled by a dynamic set of **fulfilment-event triples** `(shipment, invoice, payment)`
   that partition the order matrix `M` and **cross-cut the SO lines**; the conservation
   invariant `Σ shipments = Σ invoices = Σ payments = Σ SO lines = M` says completion is a
   *totals reconciliation*. Only the **ship** leg is SO-line-aligned (shipment lines carry
   `salesOrderLineId + qty`), so it lives on the aggregate; the **invoice** and **pay** legs are
   value-level order rollups owned by `finance.CustomerInvoice` / `Payment` + the
   `sales_order_360_view` (`invoiced_amount` / `paid_amount` vs `total_amount`). `COMPLETED` is
   the saga sequencing the triples; the former blind `markStatus(COMPLETED)` is replaced by the
   guarded `complete()`, which asserts the one leg the aggregate owns (`ordered = shipped`) —
   and because invoice sits structurally between ship and pay, `shipped ∧ paid ⇒ invoiced`, so
   the 360's `completed = shipped ∧ paid_amount ≥ total` is the complete meet. ✅ **Resolved by
   design (Slice B).** Putting the invoice axis on the SO line would have contradicted
   *deltas get aggregates, totals get projections* (§12.4 #4) and could not be populated for
   value-based deposit/balance invoices.

### 14.3 What stays deferred

- **Make/buy variant coarsening (§11)** — rides the to-order extension (dev-todo §2.43).
- **Reversals (returns / credit notes, §12.4 #2)** — break per-axis monotonicity; out of scope
  (`project_credit_notes_low_priority`).
- `WAITING_FOR_PRODUCTION` / `READY_TO_SHIP` remain schema-prep `LineStatus` values for that
  future variant work.

**Net:** the codebase now concentrates the ship-axis machine in one aggregate fold (single
writer, line-authoritative, property-tested) and locates the invoice/pay axes where the
conservation invariant puts them — value-level order rollups over the fulfilment-document
chain, owned by finance + the 360, sequenced by the saga. The model and the code agree.

---

## References / lineage

- **Harel, D. (1987)** — *Statecharts: A Visual Formalism for Complex Systems.* AND/OR
  decomposition, orthogonal regions, broadcast (Model A).
- **UML State Machines** — composite states, submachine states, orthogonal regions
  (Harel, standardised).
- **Petri nets / Coloured Petri nets (Jensen)** — token-based dynamic-cardinality
  concurrency; the natural home for routing-graph synchronisation.
- **Process algebra (CSP, Hoare; CCS, Milner)** — parallel composition of fixed,
  named communicating automata.
- **Lattice / order theory** — meet/join, the progress chain, the `classify(⊓, ⊔)` shape;
  the **product of lattices is a lattice** (componentwise meet/join) result underpins the
  multi-axis line state in §12.
- **Monoid folds / homomorphisms** — the order-insensitivity guarantee (`map`/`reduce`).
- **Abstract interpretation (Cousot & Cousot, 1977) / Galois connections** — the monotone
  `g_v : L_v → M` coarsening heterogeneous variant FSMs onto one progress lattice (§11) is
  precisely an abstraction map; "the fold runs on the abstraction" is the soundness idea.
- **Sum types / coproducts** — variant lines as a tagged union `L_make ⊎ L_buy ⊎ …`, with
  the fold defined on the coproduct via the family `g` (§11.1).
- **Pacioli (1494) → event sourcing → DDD+outbox** — *CLAUDE.md* / *docs/conventions.md*
  → *deltas get aggregates, totals get projections*; this note is that principle applied to
  composed **state**.
- In-repo: `docs/sagas.md` (the third machine — process progress), `docs/conventions.md`
  (aggregate vs projection), `SalesOrder` / `SalesOrderLine` (the worked example).
