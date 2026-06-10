# Acceptance-test DSL — a ubiquitous-language layer over the test harness

A fluent, business-readable way to **express, run, and review** a Northwood business
requirement as an executable acceptance test. The goal is that a requirement, its test,
and a reviewer's checklist are *the same three lines* — written in the ubiquitous
language (orders, shipments, invoices, payments), not in infrastructure verbs (drain the
bus, advance the worker, poke a kit field).

This document is the design + a worked order-to-cash (o2c) prototype. The DSL is a thin
layer; the engine underneath it (`test-harness`) already exists and is proven by the
`o2c/*PathTest` suite.

---

## 1. Why

A business requirement is *cross-cutting*: "when an in-stock order ships, a commercial
invoice is raised for the shipped value and COGS posts to the GL." But the code that
satisfies it is spread structurally across a sales saga transition, an inventory shipment
aggregate, a finance inbox handler, and a journal-posting service in three services. To
understand *what should happen*, a reader currently reassembles it from four files.

The acceptance test is the one artifact that can hold the whole requirement in one place.
But only if it **reads like the requirement**. Today's tests don't quite — see §3.

The DSL attacks the cost on both sides the project cares about:

1. **Express + verify** — a requirement becomes a `given / when / then` script in domain
   words; running it proves the requirement holds end-to-end across services.
2. **Read + review** — a reviewer compares the `given/when/then` against the written
   requirement line-by-line, with no harness noise in the way.

It builds directly on the project's north star — *event classes are the load-bearing
artifact for cross-service traceability*. A Northwood requirement is, almost always, a
statement about events (§4); the DSL just lets you say it in business terms while the
engine speaks events underneath.

---

## 2. What already exists (the engine)

The DSL does **not** introduce a new test runtime. It wraps the existing harness:

| Piece | Location | Role |
|---|---|---|
| `SynchronousBus` | `test-harness/.../inmemory/SynchronousBus.java` | Drains every kit's in-memory outbox to matching inbox handlers, cascading until quiescent — **same Jackson 3 serde as production**, so wire-shape regressions fall out for free. |
| `InMemoryOutboxPort` / `InMemoryInboxPort` | `test-harness/.../inmemory/` | Faithful `OutboxPort` / `InboxPort` doubles (insertion order, `(messageId, consumer)` dedup). |
| `SalesTestKit` / `InventoryTestKit` / `FinanceTestKit` (+ manufacturing, purchasing) | `test-harness/.../kits/` | Per-service composition: the **real** application services, the **real** saga manager + worker shell, **every real** inbox handler, wired to in-memory adapters and registered on the bus. |
| `o2c/OrderToCashHappyPathTest` etc. | `test-harness/.../o2c/` | Eight cross-service scenarios already drive the real saga state machine via the bus. |

The faithful bits worth protecting (§8): real handlers, real saga worker, real serde, real
application services. The DSL must drive *through* these, never around them.

---

## 3. The problem with today's test — before / after

Today's `OrderToCashHappyPathTest` (abridged, verbatim shape):

```java
SalesTestKit sales = new SalesTestKit(bus, json);
InventoryTestKit inventory = new InventoryTestKit(bus, json);
FinanceTestKit finance = new FinanceTestKit(bus, json);

sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
UUID productId = UUID.randomUUID();
sales.productCards.put(productId, new BigDecimal("100.00"), Currencies.AUD);
inventory.seedStock(productId, new BigDecimal("50"));

UUID orderId = sales.placeOrder(new PlaceOrderCommand(
    "SO-9001", "CUST-001", LocalDate.of(2026, 5, 20), Currencies.AUD, null,
    List.of(new OrderLine(productId, "FG-001", "Finished Good 1",
        new BigDecimal("3"), null, BigDecimal.ZERO))));

sales.advanceSagaWorker();
bus.drain();

assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
    .isEqualTo(SalesOrderFulfilmentSaga.READY_TO_SHIP);

UUID customerId = sales.customers.findByCode("CUST-001").orElseThrow().customerId();
SalesOrderLine placedLine = sales.orders.findById(SalesOrderId.of(orderId))
    .orElseThrow().lines().get(0);
inventory.shipmentService.post(new PostShipmentCommand(
    "SHIP-001", orderId, "SO-HAPPY-1", customerId, "Acme Corp", WarehouseCodes.MAIN,
    List.of(new ShipmentLineRequest(placedLine.lineId(), productId,
        "FG-001", "Finished Good 1", new BigDecimal("3"), new BigDecimal("60.00")))));
bus.drain();
// ... resolve invoiceHeaderId from finance.customerInvoices, record payment, drain ...

assertThat(sales.outbox.all()).extracting(OutboxRow::getEventType)
    .contains(SalesOrderPlaced.EVENT_TYPE, StockReservationRequested.EVENT_TYPE,
        SalesOrderReadyToShip.EVENT_TYPE, SalesOrderShipped.EVENT_TYPE);
```

What a reviewer has to wade through that isn't the requirement:

- **Infrastructure verbs** — `advanceSagaWorker()`, `bus.drain()` after every step. These
  are "let the async machinery run," not business facts.
- **Identity juggling** — `UUID productId = randomUUID()`, then resolving `customerId` and
  `placedLine.lineId()` back out of kits to build the next command.
- **Positional command records** — `new PostShipmentCommand("SHIP-001", orderId, "SO-HAPPY-1",
  customerId, "Acme Corp", MAIN, List.of(...))` — wire plumbing, not "the warehouse ships 3 units."
- **Outcome stated as an outbox event-type list** — true and useful, but it reads as
  "these strings appeared," not "the order was invoiced and settled."

The **same** scenario through the DSL:

```java
scenario("in-stock order: ships, invoices, and settles in full")

  .given(a_customer("CUST-001", "Acme Corp"))
  .and(a_product("FG-001", "Finished Good 1").pricedAt(money(100)))
  .and(stock_on_hand("FG-001", qty(50)).at(MAIN))

  .when(customer("CUST-001").places_order("SO-9001")
        .line("FG-001", qty(3)))
  .then(order("SO-9001").reaches(READY_TO_SHIP))

  .when(warehouse(MAIN).ships("SO-9001")
        .line("FG-001", qty(3)).at_unit_cost(money(60)))
  .then(order("SO-9001").reaches(INVOICE_CREATED))
  .and(a_commercial_invoice().for_order("SO-9001").totalling(money(300)))

  .when(customer("CUST-001").pays(money(300)).against("SO-9001"))
  .then(order("SO-9001").is_completed())
  .and(events_published(
        SalesOrderPlaced.EVENT_TYPE, StockReserved.EVENT_TYPE, ShipmentPosted.EVENT_TYPE,
        CustomerInvoiceCreated.EVENT_TYPE, CustomerPaymentReceived.EVENT_TYPE));
```

No `drain`, no `advanceSagaWorker`, no UUIDs, no positional records. Business identifiers
(`"SO-9001"`, `"FG-001"`, `MAIN`) thread the scenario; the engine resolves them to UUIDs
and commands behind the scenes. The infrastructure verbs are folded into one faithful
primitive — `settle()` (§6) — that runs after every `when`.

---

## 4. The requirement template it serves

Almost every cross-service Northwood business rule reduces to one of two shapes. The DSL is
designed so each maps cleanly onto `given / when / then`.

**(a) Invariant** — a single-aggregate rule. Best left to a domain unit test
(`SalesOrderTest`-style); the DSL is overkill. Example: "you cannot ship more than was
reserved."

**(b) Policy / reaction** — `trigger → guard → outcome`. This is ~80% of cross-service
logic and the DSL's sweet spot:

| Template field | Maps to | o2c example (the shipment→invoice rule) |
|---|---|---|
| **guard** (context that must hold) | `given …` | an in-stock order sitting at `ready_to_ship` |
| **trigger** (the event/command) | `when …` | the warehouse ships the order |
| **outcome** (state change + emitted events) | `then …` | a commercial invoice for the shipped value exists; saga at `invoice_created`; COGS posted |

So a requirement written as a `trigger/guard/outcome` stanza in a PR description *is* the
skeleton of its acceptance test. Write the stanza, then transcribe it. Review is the
reverse: read the `given/when/then`, confirm it is the stanza.

> Recommended PR/commit convention going forward: state new cross-service requirements as a
> `trigger / guard / outcome` stanza, and name the acceptance test after it. (Locking that
> stanza vocabulary is the natural companion follow-up to this DSL — see §10.)

---

## 5. Vocabulary

Nouns are business identifiers (strings/codes the reader recognises); the World (§6)
resolves them to UUIDs. Verbs are domain actions; each `when` action ends by settling the
world.

### Given — seed the world (the guard)

| DSL | Resolves to |
|---|---|
| `a_customer(code, name)` | `sales.customers.put(code, name, ACTIVE)` |
| `a_product(code, name).pricedAt(Money)` | random `productId`; `sales.productCards.put(...)`; registers `code → productId` |
| `stock_on_hand(productCode, Qty).at(Warehouse)` | `inventory.seedStock(productId, qty)` |

### When — drive a domain action (the trigger)

| DSL | Resolves to |
|---|---|
| `customer(code).places_order(orderNo).line(productCode, Qty)…` | builds `PlaceOrderCommand`; `sales.placeOrder`; registers `orderNo → orderId`; **settles** |
| `warehouse(wh).ships(orderNo).line(productCode, Qty).at_unit_cost(Money)` | resolves `lineId`, `customerId`; builds `PostShipmentCommand`; `inventory.shipmentService.post`; **settles** |
| `customer(code).pays(Money).against(orderNo)` | resolves `invoiceHeaderId`; builds `RecordCustomerPaymentCommand`; `finance.paymentService.recordCustomerPayment`; **settles** |
| `customer(code).cancels(orderNo).because(reason)` | `sales.cancel(orderId, reason)`; **settles** |

### Then — assert the outcome

| DSL | Asserts |
|---|---|
| `order(orderNo).reaches(SagaState)` | saga state == constant (e.g. `READY_TO_SHIP`) |
| `order(orderNo).has_status(SalesOrder.Status)` | header-status projection |
| `order(orderNo).is_completed()` | saga `COMPLETED` **and** status `COMPLETED` |
| `a_commercial_invoice().for_order(orderNo).totalling(Money)` | a `CustomerInvoice` (type COMMERCIAL) exists for the order with that total |
| `events_published(EVENT_TYPE…)` | union of all kits' outboxes contains those `event_type`s |

### Value helpers (tiny, scale-correct)

| Helper | Produces |
|---|---|
| `money(100)` | `BigDecimal("100.00")` (or a `Money` VO in `Currencies.AUD`) |
| `qty(3)` | `BigDecimal("3")` |
| `MAIN`, `READY_TO_SHIP`, `INVOICE_CREATED` | re-exported real constants (`WarehouseCodes.MAIN`, `SalesOrderFulfilmentSaga.*`) — no new vocabulary, just imports |

**The DSL invents no business vocabulary.** Every state, warehouse code, currency, and
`EVENT_TYPE` is a real constant from the production/event jars. The fluent words
(`places_order`, `ships`, `pays`) name real kit operations. If a reader does not recognise
a noun, that is a signal the ubiquitous language is thin there — not that the DSL hid
something.

---

## 6. Architecture — `World`, identifiers, `settle()`

The DSL is one new package under `test-harness` (test scope), no production code touched.

```
test-harness/.../dsl/
  Scenario.java       // entry point: scenario("…").given(…).when(…).then(…)
  World.java          // owns the bus + the three kits + the identifier registry
  Money.java, Qty.java, Ids.java   // value helpers + the code→UUID registry
  give/  When/  Then/  // the fluent builders (seed / action / assertion)
```

**`World`** is the single mutable fixture. It constructs `SynchronousBus` + the kits
exactly as the current tests do, and holds a **registry** mapping business identifiers to
the UUIDs the engine generates:

```
customerCode → customerId      productCode → productId
orderNumber  → salesOrderId     orderNumber → (resolved) invoiceHeaderId, lineId…
```

Every `given` seeds and registers; every `when` looks up by identifier, builds the real
command, calls the real service, then **settles**.

**`settle()` is the one faithfulness primitive that replaces the scattered
`advanceSagaWorker()` + `bus.drain()` calls.** It models "let the asynchronous machinery
run to quiescence":

```java
void settle() {
    do {
        sales.advanceSagaWorker();   // real worker shell — one drain pass
        bus.drain();                 // real bus — cascade handlers to quiescence
    } while (/* any kit's outbox still has pending rows, or a worker is still claimable */);
}
```

This is deliberately the *only* place infrastructure timing lives. Tests never call it; it
runs implicitly after each `when`. (Where a test must observe an intermediate, not-yet-
settled state — rare — a `.without_settling()` escape hatch on the action keeps that
possible.)

> **Faithfulness note.** `settle()` must drive the **real** `SalesOrderFulfilmentSagaWorker`
> shell and the **real** `SynchronousBus` (same handlers, same serde). It must not shortcut a
> saga transition or hand-craft an event. The whole value of the DSL is that the *only* thing
> it abstracts is *naming and timing* — never *behaviour*. If `settle()` ever forges a state
> the production worker wouldn't reach, the tests become fiction. This is the invariant to
> guard in review.

---

## 7. Worked o2c example — the full happy path

The complete scenario, as it would live in `test-harness/.../dsl/o2c/`:

```java
import static com.northwood.testharness.dsl.Scenario.scenario;
import static com.northwood.testharness.dsl.Dsl.*;          // a_customer, money, qty, …
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.*;  // READY_TO_SHIP, …

class OrderToCashHappyPathDsl {

    @Test
    void in_stock_order_ships_invoices_and_settles_in_full() {
        scenario("in-stock order: ships, invoices, and settles in full")

          // ── guard: a priced product, in stock, for a known customer ──
          .given(a_customer("CUST-001", "Acme Corp"))
          .and(a_product("FG-001", "Finished Good 1").pricedAt(money(100)))
          .and(stock_on_hand("FG-001", qty(50)).at(MAIN))

          // ── trigger: customer places an order for 3 units ──
          .when(customer("CUST-001").places_order("SO-9001")
                .line("FG-001", qty(3)))
          // ── outcome: full reservation shortcuts straight to ready-to-ship ──
          .then(order("SO-9001").reaches(READY_TO_SHIP))
          .and(order("SO-9001").has_status(SalesOrder.Status.IN_FULFILMENT))

          // ── trigger: the warehouse ships all 3 units ──
          .when(warehouse(MAIN).ships("SO-9001")
                .line("FG-001", qty(3)).at_unit_cost(money(60)))
          // ── outcome: a commercial invoice for 3 × 100 is raised ──
          .then(order("SO-9001").reaches(INVOICE_CREATED))
          .and(a_commercial_invoice().for_order("SO-9001").totalling(money(300)))

          // ── trigger: customer settles the invoice in full ──
          .when(customer("CUST-001").pays(money(300)).against("SO-9001"))
          // ── outcome: order completes; the expected events crossed services ──
          .then(order("SO-9001").is_completed())
          .and(events_published(
                SalesOrderPlaced.EVENT_TYPE,
                StockReservationRequested.EVENT_TYPE,
                StockReserved.EVENT_TYPE,
                SalesOrderReadyToShip.EVENT_TYPE,
                ShipmentPosted.EVENT_TYPE,
                SalesOrderShipped.EVENT_TYPE,
                CustomerInvoiceCreated.EVENT_TYPE,
                CustomerPaymentReceived.EVENT_TYPE));
    }
}
```

Every line is either the requirement (`given/when/then`) or a real constant. The three
`when`s are the three triggers; the engine settles after each. Compare to §3 — same
behaviour exercised, same handlers and serde underneath, but the noise is gone.

---

## 8. Faithfulness contract — what the DSL may and may not hide

The DSL earns trust only by being honest about its seams.

**It abstracts — naming and timing only:**

- Business identifiers instead of UUIDs (`"FG-001"` not `productId`).
- Fluent verbs instead of positional command records.
- `settle()` instead of hand-placed `advanceSagaWorker()` / `bus.drain()`.

**It must NOT abstract — behaviour:**

- It drives the **real** application services (`SalesOrderService`, `ShipmentService`,
  `PaymentService`), the **real** saga manager + worker, and the **real** inbox handlers.
- Events round-trip through the **real** Jackson 3 serde on the **real** `SynchronousBus`.
  Wire-shape breakage still fails the test — that property is preserved, not bypassed.
- It forges no events and shortcuts no saga transitions.

**Known leaks / limits — state them, don't paper over:**

- **Not Kafka.** `SynchronousBus` is in-memory and synchronous. It does **not** cover
  partition ordering, consumer rebalancing, retry/backoff, or DLT redrive — those stay the
  job of the `*SeamIT` / `*DeliveryIT` Testcontainers+Kafka tests. The DSL verifies
  *business outcome*, not *delivery mechanics*. This boundary is a feature: keep it.
- **`settle()` quiescence ≠ real-time interleaving.** It runs the world to a fixed point;
  it cannot express "event A overtakes event B across partitions." Genuinely
  ordering-sensitive requirements need a seam IT.
- **One in-memory DB-less world.** No real SQL, no CHECK constraints, no triggers (e.g. the
  stock-allocation trigger). Persistence fidelity remains the `Jdbc*IT` tests' job. A
  requirement that *is* a DB constraint must still have its `Jdbc*IT`.

The DSL is the **business-outcome** tier of a three-tier story it does not replace:
domain unit tests (invariants) · acceptance DSL (cross-service outcomes) · `Jdbc*IT` +
`*SeamIT` (persistence + delivery fidelity).

---

## 9. Implementation roadmap

Small, because the engine exists. Suggested slices:

1. ✅ **`World` + registry + `settle()`** — wrap the three kits; prove `settle()` reproduces
   the happy path's saga progression with zero hand-drains. (Re-derive
   `OrderToCashHappyPathTest`'s assertions through `World` directly, no fluent sugar yet.)
   Shipped: `World` + `OrderToCashHappyPathWorldTest`.
2. ✅ **Given/When/Then builders for o2c** — the vocabulary in §5, only what the happy path
   needs. Land `OrderToCashHappyPathDsl` (§7) green alongside the existing test. Shipped:
   `Scenario` + `Dsl` + `OrderToCashHappyPathDsl`.
3. ✅ **Port one more path** — the deposit branch (`OrderToCashDepositPathTest`), which
   pressure-tests the vocabulary hardest: two invoice types, two payments, and the
   `maintain_allocation_totals` trigger stand-in. Shipped: `OrderToCashDepositPathDsl`, with
   new branch vocabulary `with_deposit(percent(50))`, `pays(…).against_deposit_on/
   against_balance_on(…)`, and `a_deposit_invoice()` / `a_balance_invoice()`. The auto-settle
   model held cleanly — each step settles to a parked saga state (`deposit_invoiced`,
   `ready_to_ship`), so no `.without_settling()` escape hatch was needed. The faithfulness
   seam that surfaced: `settle()` runs the deposit payment all the way through to
   `ready_to_ship` (the worker reserves stock once the deposit settles), and `World.payInvoice`
   stamps the allocation *after* settling — the per-payment stand-in for the production trigger
   so a later balance payment computes order-level settlement correctly. (Cancellation/
   compensation remains an open branch to port when its vocabulary is needed — it will likely
   be the one that forces `.without_settling()`, since the original cancels pre-worker.)
4. **Decide rollout** — once the vocabulary holds across 2–3 paths, either migrate the o2c
   suite or keep the DSL for *new* requirements only and leave the existing tests. (Lean:
   new requirements adopt it; migrate opportunistically.)

Non-negotiable in every slice: the DSL adds **no** production code and drives only real
services/handlers/serde (§8).

---

## 10. Open decisions

- **Plain-Java fluent DSL vs Cucumber/Gherkin.** Recommendation: **plain Java.** For a
  single-author showcase, Gherkin's `.feature`-file + glue-code indirection costs more
  (lost IDE navigation, a second artifact to keep in sync) than the English-ish surface
  buys. The fluent builders already read as English without leaving Java.
- **`Money`/`Qty` VOs vs bare `BigDecimal`.** Lean to thin VOs so scale is correct by
  construction and `money(100).totalling(...)` reads right; reuse `shared.domain` Money if
  it fits.
- **Companion requirement-template.** The `trigger/guard/outcome` stanza (§4) is the
  natural next artifact — it makes the *writing* side as cheap as the DSL makes the
  *reading* side. Worth its own short doc once the DSL vocabulary settles.
- **Scope creep guard.** The DSL is for cross-service *business outcomes*. Resist pulling
  delivery-mechanics or persistence assertions into it — those tiers exist (§8) and
  blurring them is how the abstraction starts lying.
```
