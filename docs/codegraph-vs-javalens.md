# codegraph vs JavaLens — two code-intelligence tools, compared on the saga subsystem

A side-by-side of the two code-intelligence MCP servers wired into this repo, using the saga subsystem as the worked example. Both were pointed at the same code (the `SagaManager` / `SagaInstance` base, the three `*SagaManager` interfaces + their `Jdbc*` impls, the workers, and the inbox handlers) and asked the same structural questions. This note records where they agreed, where each added value, and the practical "which one when" takeaway.

## What each tool is

- **codegraph** — a pre-built SQLite knowledge graph of every symbol, edge, and file. A query returns the **verbatim source** of the relevant symbols grouped by file, plus the **caller → callee trail** among them, in one capped call. Sub-millisecond reads; the index lags edits by ~1s. It is scoped to the query — it returns what's relevant, not an exhaustive enumeration.
- **JavaLens** — a JDT-backed (Eclipse compiler) semantic server. It answers from a fully type-checked model: complete type hierarchies, exhaustive member lists, implementations, references, diagnostics. Strict disk-sync (verified against files at query time). It returns **signatures and structure**, not method bodies.

## The comparison (saga subsystem)

### Where they agree — structure, 100% match

| Fact | codegraph | JavaLens |
|---|---|---|
| `SagaManager<S, P>` abstract; `S extends SagaInstance`, `P extends SagaPort<S>` | ✓ | ✓ (explicit `typeParameterBounds`) |
| Base fields: `log`, `sagaPort`, `tx`, `leaseTtl`, `retryBackoff` | ✓ | ✓ |
| Base methods: `drain` (public), `activeStates` (abstract) | ✓ | ✓ |
| `SagaInstance` subtypes: `SalesOrderFulfilmentSaga`, `WorkOrderSaga`, `PurchaseToPaySaga` | ✓ | ✓ (+ 2 test sagas) |
| `SagaManager` impls: the three `Jdbc*SagaManager` | ✓ | ✓ (+ 2 test managers) |
| 0 compiler diagnostics on the saga files | — (not its job) | ✓ |

JavaLens additionally enumerated the **test doubles** (`SagaManagerTest$TestSagaManager`, `SagaManagerProxyTest$TestSaga`). codegraph's "blast radius" *hinted* at the proxy test (it listed it as a dependent) but JavaLens named every subtype exactly. That proxy test is the one guarding the "`drain` must not be `final`" CGLIB gotcha documented in `sagas.md`.

### Where each pulled ahead

**codegraph won the first pass** — drawing the saga sequence diagrams needed the actual transition *targets* and *guards*: the `transitionTo(...)` arguments, the `parkUntil(...)` calls, the source-state guard conditions, the `saga.data` set-drain logic. Those live in method **bodies**, which codegraph returns verbatim and JavaLens does not. One `codegraph_explore` per saga produced enough to author each flow.

**JavaLens won the verification pass** — its complete, type-checked member listing is authoritative for *"did I cover every transition?"*. It caught four transitions the codegraph-scoped diagrams had omitted:

| Saga | Transition missed in first draft | Behaviour |
|---|---|---|
| Sales | `applyLineReservationChanged(orderId, lineId, lineIsShort)` | line-amendment reply; adds/removes a short line while parked |
| Sales | `applyCompensationAck(orderId, legId, failed)` | multi-leg compensation drain → `compensated` / `compensation_failed` |
| Manufacturing | `cancel(workOrderId)` | order-pegged compensation terminate → `cancelled` |
| Purchasing | `applySupplierInvoiceRejected(poId)` | `goods_received → failed` (manual invoice reject) |

None contradicted the diagrams — they were additional branches (since folded into `sagas.md`). The crucial point: **absence from codegraph's output is not proof of absence in the code**, because codegraph returns query-scoped results. JavaLens's enumeration *is* exhaustive, so it's the right tool to prove completeness.

## The takeaway — complementary, not competing

- Reach for **codegraph first** for understanding and authoring: "how does X work", flows, bodies, call paths, blast radius — one call, verbatim source, fewest round-trips.
- Reach for **JavaLens to verify and refactor**: exhaustive member/hierarchy/reference enumeration, "have I covered every case", compiler diagnostics, and the JDT-backed refactor/rename operations codegraph doesn't offer.

They agreed everywhere they overlapped; the value was in the non-overlap. codegraph's bodies made the diagrams *possible*; JavaLens's exhaustive enumeration made them *complete*.

## Reproducing this

- codegraph: `codegraph explore "SagaManager SagaInstance SalesOrderFulfilmentSaga ... advance"` (or the `codegraph_explore` MCP tool). Returns bodies + trails.
- JavaLens: `load_project` once, then `analyze_type` on each `*SagaManager` interface (exhaustive method list), `get_type_hierarchy` on `SagaInstance`, `find_implementations` on `SagaManager`. Returns signatures + hierarchy + diagnostics.
