# Northwood — The Domain

The problem Northwood solves, in its own words and with no technology. This document is the top of
the design chain: the glossary is the vocabulary every lower document and all the code must use, and
the model (aggregates, values, events, services, rules) is what the code is named after. Where the
code says something this document does not, this document is amended first, then the code follows.

Rebuilt on 2026-10-24 from the earlier domain model and business requirements, checked against the
code as it stands. Places where the code and the domain disagree are listed in section 13.

---

## 1. The reference point

**What is Northwood measured against? A classic ERP/MRP system for a small manufacturer: order-to-cash,
procure-to-pay, reorder-point replenishment with bill-of-materials explosion, and perpetual-inventory
double-entry accounting.** It departs from that practice on purpose in the places listed below.

Northwood Furniture makes and sells a fixed catalogue of standard products: chests, tables and their
sub-assemblies, built from bought raw materials. The established practice it follows:

- **Order-to-cash**: a customer places a sales order, stock is reserved, goods are shipped, the
  customer is invoiced and pays.
- **Procure-to-pay**: demand raises a purchase requisition, it becomes a purchase order sent to a
  supplier, goods are received, the supplier invoice is matched three ways and paid.
- **MRP, reactive end**: a reorder point per product plus BOM explosion on a work order. This is
  MRP I without time phasing.
- **Perpetual inventory with standard costing**: every movement of goods posts a balanced journal
  entry, and finished goods carry their full standard cost (material plus conversion).

Where Northwood departs:

| Practice | Northwood |
|---|---|
| Time-phased MRP (net demand against supply over a horizon) | Not modelled. Supply fires when a reorder point is breached or a shortage is found. The planning time fence is the one exception (section 5.2). |
| Supply requested by whoever finds the need | **Inventory is the only replenishment broker.** Sales, manufacturing and purchasing never ask each other for supply. |
| Make-to-order as a common mode | **Make-to-stock is the default.** To-order (make-to-order, buy-to-order) is opt-in per product. |
| Finite-capacity scheduling | Not modelled. A routing gives cost, not a capacity limit. |
| Price lists, discounts, multi-currency, multi-jurisdiction tax, returns | Not modelled (section 12). |

---

## 2. Subdomains and the context map

**What are the parts of the problem, and how do they talk? Six subdomains, each its own bounded
context. Manufacturing is the core. They share facts only by publishing events, and each keeps its
own copy of what it needs from the others.**

### 2.1 Subdomains

| Subdomain | Kind | The question it answers |
|---|---|---|
| **Manufacturing** | Core | How do we build a finished good, what does it cost, and is a build in progress? |
| **Inventory** | Supporting | What stock is where, what is reserved against it, and what needs replenishing? |
| **Sales** | Supporting | What did the customer order, and how far along is that order? |
| **Purchasing** | Supporting | What must we buy, from whom, at what price, and has it arrived? |
| **Finance** | Supporting | What do we owe, what are we owed, and do the books balance? |
| **Product** | Generic | What can we make, buy or sell, and how is each item replenished? |

Manufacturing is the core because the know-how is there: BOMs with sub-assemblies, scrap factors,
routings, conversion cost and WIP costing. The supporting subdomains are necessary but common to
every business. Product is generic and could be an off-the-shelf product-information system.

**Reporting** is not a subdomain. It has no language of its own and makes no decisions. It holds the
views of section 11, built from everyone else's events.

### 2.2 Context map

| Upstream | Downstream | Relationship | What flows |
|---|---|---|---|
| Product | everyone | Open host, published language | The product master and every change to it. Each consumer keeps a **product card**. |
| Sales | Inventory | Customer/supplier (Sales is the customer) | Stock reservation requests, cancellation requests. Inventory answers with reservation outcomes and cancellation acknowledgements. |
| Inventory | Manufacturing, Purchasing | Customer/supplier (Inventory is the customer) | Replenishment requests, and cancellation of order-pegged supply. They answer with dispatched, undispatchable and cancellation-applied. |
| Manufacturing | Inventory | Customer/supplier, both ways | Raw-material reservation requests and completions. Inventory answers with reservation outcomes and shortages. |
| Inventory, Purchasing | Finance | Customer/supplier | Goods receipts, shipments and stock adjustments become postings. Goods receipts and purchase orders anchor the three-way match. |
| Sales | Finance | Customer/supplier | Up-front invoice requests, shipments and order terminals (for refunds). |
| Finance | Sales, Purchasing | Customer/supplier | Invoice created, payment received, supplier invoice approved or rejected, supplier paid. |
| everyone | Reporting | Conformist | Every event, as published. |

Three rules hold the map together:

1. **Manufacturing and Purchasing never talk to each other.** A shortage in either one goes through
   an Inventory replenishment request.
2. **Sales never asks Manufacturing or Purchasing for anything.** It asks Inventory for stock and
   waits.
3. **No subdomain reads another's records.** A fact crosses a boundary only as an event, and the
   receiver keeps its own copy.

No outside system is connected yet, so there is no anti-corruption layer. Carriers (Inventory),
payment gateways (Finance) and supplier EDI (Purchasing) will each need one.

---

## 3. The ubiquitous language

**What do the words mean? Each term below has one meaning across the whole domain. Each word has one kind
(noun, verb or adjective) and is never used as another.** Terms are grouped by the subdomain that owns
them. A shared term is listed once, under its owner.

### 3.1 Shared

| Term | Kind | Meaning | Does not mean |
|---|---|---|---|
| **Product** | noun | Anything Northwood makes, buys or sells, identified by its SKU. | A product card (a copy held by another subdomain). |
| **SKU** | noun | The product's code: capital letters, digits, `_` and `-`, starting with a letter, 2 to 50 characters. It never changes. | The product's name. |
| **Money** | noun | An amount in one currency. Two amounts in different currencies cannot be added. | An amount with its exchange rate. The rate is held by the document that carries the money. |
| **Quantity** | noun | An amount in one unit of measure. Two quantities in different units cannot be added. | A count with no unit. |
| **Unit of measure** | noun | The unit a product is counted in (EA, KG, M). Fixed reference data. | |
| **Currency** | noun | An ISO 4217 code. AUD is the base currency. | |
| **Warehouse** | noun | A place stock is kept. Fixed reference data. | |
| **Product card** | noun | A subdomain's own copy of the product facts it needs, kept up to date from Product's events. | The product master. |
| **Snapshot** | verb | To copy a value onto a document when the document is made, so later changes to the source do not change the document. | |
| **Saga** | noun | A long-running business transaction that spans subdomains. It has its own states, waits for answers, and undoes its steps in reverse if it is stopped. | A database transaction. |
| **Compensation** | noun | The undoing of a Saga's completed steps after a cancellation. | A refund (one kind of compensation step). |
| **Compensation leg** | noun | One undo step that must be acknowledged before compensation is complete: one per piece of committed order-pegged supply. | |
| **Terminal** | noun | A state a lifecycle cannot leave. | |

### 3.2 Product

| Term | Kind | Meaning | Does not mean |
|---|---|---|---|
| **Product type** | noun | Raw material, semi-finished good, finished good or service. Sets the defaults for make-vs-buy and valuation class. | The valuation class. |
| **Raw material** | noun | A product that is bought and used in a build. | |
| **Semi-finished good** / **sub-assembly** | noun | A product built to go into another product. *Semi-finished good* is its product type; *sub-assembly* is its role on a BOM. | |
| **Finished good** | noun | A product built to be sold. | |
| **Service** | noun | A product that is sold or bought but never stocked or built. | |
| **Make-vs-buy** | noun | Two independent flags on a product: *manufactured* and *purchased*. At least one is set. Both may be set. | The replenishment strategy. |
| **Sellable** | adjective | A product that may appear on a sales order. | |
| **Replenishment strategy** | noun | *To stock* (supply fills the shared pool) or *to order* (supply is pegged to one sales-order line). Independent of make-vs-buy. | One of the four combined modes (make-to-stock, make-to-order, buy-to-stock, buy-to-order). These are derived, not stored. |
| **Reorder policy** | noun | A reorder point and a reorder quantity. Zero for both means no automatic replenishment. | |
| **Reorder point** | noun | The on-hand level below which automatic replenishment fires. | |
| **Reorder quantity** | noun | How much each automatic replenishment asks for. | |
| **Sales price** | noun | The one list price of a product. Copied onto a sales line at capture. | A price list. |
| **Standard cost** | noun | What one unit is valued at: material plus conversion, rolled up through the BOM for a manufactured product. | The average cost actually paid. |
| **Valuation class** | noun | Raw materials, semi-finished goods or finished goods. Decides which accounts a movement posts to. | The product type, even though the values look alike. |
| **Planning time fence** | noun | A number of days. An order for a fenced product waits until *requested delivery date − fence* before stock is reserved. | |
| **Active BOM** | noun | Which BOM manufacturing uses for this product. | |
| **Approved vendor** | noun | A supplier allowed to supply a product. One is marked preferred. | |
| **Discontinue** | verb | To stop a product being used for new demand. Every past fact keeps referring to it. | To delete. |

### 3.3 Sales

| Term | Kind | Meaning | Does not mean |
|---|---|---|---|
| **Customer** | noun | Someone who buys from Northwood. | A debtor (Finance's view of the same party). |
| **Sales order** | noun | A customer's commitment to buy: a header and one or more lines. | A purchase order or a work order. Always say the full name. |
| **Sales-order line** | noun | One product and quantity on a sales order, with its price and tax copied at capture. | |
| **Place** | verb | To capture a sales order. There is no draft or confirm step. | |
| **Ordered quantity** | noun | How much the line asks for. | |
| **Reserved quantity** | noun | How much of the line inventory holds for it. | |
| **Shipped quantity** | noun | How much of the line has left the warehouse. | |
| **Backordered quantity** | noun | Ordered minus shipped, never below zero. Calculated, never stored. | |
| **Requested delivery date** | noun | The customer's wish. It acts only through the planning time fence. | A promise. |
| **Payment terms** | noun | *On shipment* (invoiced at shipment, paid later), *prepayment* (paid in full before shipment), *cash on delivery* (paid at shipment), or *deposit* (a percentage paid up front, the rest at shipment). Plural on purpose: it is the trade term. | |
| **Deposit percent** | noun | For deposit terms only: the share paid up front, more than 0 and less than 100. | |
| **Up-front invoice** | noun | A prepayment or deposit invoice, raised when the order is placed. | |
| **Order status** | noun | The sales order's label: a fold of its line statuses, or one of three terminals (section 6.3). | A permission. Rules read the lines, not this label. |
| **Line status** | noun | Open, partially reserved, reserved, partially shipped, shipped, or cancelled. | |
| **Request cancellation** | verb | To ask for a sales order to be cancelled. Inventory decides whether the cancellation or a shipment wins. | To cancel. |
| **Cancel** | verb | To end a sales order before shipment because the customer asked. | To reject. |
| **Reject** | verb | To end a sales order because the system cannot supply it. | To cancel. |
| **Complete** | verb | To close a sales order that is fully shipped and fully paid. | |
| **Cancellation outcome** | noun | Derived: none, cancelling, cancelled, or cancellation rejected (a shipment won). | |

### 3.4 Inventory

| Term | Kind | Meaning | Does not mean |
|---|---|---|---|
| **On-hand** | noun | The physical quantity in a warehouse, including what is reserved. | |
| **Reserved** | noun | The on-hand quantity committed to sales orders or work orders. | |
| **Available** | noun | On-hand minus reserved: what may be promised to new demand. | Available-to-promise (the forward-looking view). |
| **WIP balance** | noun | Sub-assembly units built but not yet consumed by their parent work order. | The WIP account in Finance. |
| **Stock balance** | noun | On-hand, reserved and average cost for one product in one warehouse. A running total of stock movements. | An aggregate. |
| **Tracking mode** | noun | *Tracked* (has balances) or *not tracked* (services skip inventory). | |
| **Stock movement** | noun | One recorded change to on-hand: purchase receipt, sales shipment, finished-goods receipt, adjustment in, or adjustment out. Never changed once written. | |
| **Stock reservation** | noun | A hold on stock for exactly one sales order or one work order. | |
| **Shortage** | noun | The part of a reservation that could not be held. | |
| **Goods receipt** | noun | A record that goods arrived against a purchase order. Never changed once posted. | |
| **Shipment** | noun | A record that goods left for a customer against a sales order. Never changed once posted. | A payment. |
| **Stock adjustment** | noun | A one-line correction to on-hand (cycle count, breakage, write-off), in or out. | |
| **Post** | verb | To make a goods receipt, shipment, stock adjustment or journal entry final. | |
| **Replenishment request** | noun | Inventory's request for more supply of one product in one warehouse, sent to manufacturing or purchasing. | A purchase requisition (one possible result). |
| **Replenishment reason** | noun | *Reorder-point breach*, *work-order shortage*, *sales-order shortage*, or *order-pegged*. | |
| **Target** | noun | Which subdomain will supply a replenishment request: manufacturing or purchasing. Chosen from make-vs-buy. | |
| **Dispatch** | verb | (Of manufacturing or purchasing) to accept a replenishment request and raise a work order or purchase requisition for it. | To ship. |
| **Undispatchable** | adjective | A replenishment request the target cannot act on: no active BOM, or no approved vendor. | |
| **Order-pegged supply** | noun | Supply raised for one to-order sales-order line, for its full quantity, and held only for that line. | Stock in the shared pool. |
| **Shared pool** | noun | Stock that any order may draw on. | |

### 3.5 Manufacturing

| Term | Kind | Meaning | Does not mean |
|---|---|---|---|
| **BOM** (bill of materials) | noun | The recipe for one product: a version, a status, and lines. | A routing. |
| **BOM line** | noun | One component, its quantity per finished unit, and its scrap factor. | |
| **Component kind** | noun | *Raw* or *sub-assembly*. A sub-assembly line makes the recipe multi-level. | |
| **Scrap factor** | noun | The extra percentage of a component that is lost in the build. | |
| **Activate** | verb | To make a draft BOM the active one for its product. | |
| **Routing** | noun | The ordered operations needed to build a product, each at a work centre with planned setup and run minutes. | A BOM. |
| **Work centre** | noun | A place operations are done, with a labour rate and an overhead rate per minute. | |
| **Work order** | noun | An instruction to build a planned quantity of one product. | A sales order or a purchase order. |
| **Release** | verb | To create a work order from the active BOM and routing, ready for the shop floor. | To release a reservation (see section 4). |
| **Work-order material** | noun | One component line copied from the BOM at release. | |
| **Work-order operation** | noun | One operation copied from the routing at release: planned, in progress, completed or skipped. | |
| **Material status** | noun | Reservation pending, reserved, partially reserved, or shortage. | The work order's status. |
| **Child work order** | noun | A work order released to build a sub-assembly for a parent work order. | |
| **Consume** | verb | (Of a parent work order) to use up its children's sub-assembly output when it completes. | |
| **Conversion cost** | noun | Labour plus overhead: minutes times the work centre's rates. | Material cost. |
| **Cost roll-up** | noun | The calculation of a manufactured product's standard cost through its BOM: each component's cost × quantity × (1 + scrap factor), plus own-routing conversion. | |
| **Priority** | noun | A planner-set order of work on the production board. | |

### 3.6 Purchasing

| Term | Kind | Meaning | Does not mean |
|---|---|---|---|
| **Supplier** | noun | Someone Northwood buys from. | A creditor (Finance's view of the same party). |
| **Supplier product price** | noun | One supplier's price for one product, in one currency, from a date, for a minimum quantity. | The sales price. |
| **Price tier** | noun | A supplier product price that applies from a minimum quantity. | |
| **Purchase requisition** | noun | An internal request to buy, raised by hand (*manual*) or from a replenishment request (*stock replenishment*). | A purchase order. |
| **Convert** | verb | To turn an approved purchase requisition into a purchase order. | |
| **Purchase order** | noun | A supplier-facing order to buy. | A sales order or a work order. |
| **Approve** | verb | (Of a buyer) to accept a draft purchase order, which sends it to the supplier. | |
| **Three-way match** | noun | The check that a supplier invoice agrees with its purchase order line (price, within tolerance) and its goods receipt (quantity, exactly). | |
| **Price tolerance** | noun | How far a supplier invoice price may differ from the purchase order price and still match. | |

### 3.7 Finance

| Term | Kind | Meaning | Does not mean |
|---|---|---|---|
| **Account** | noun | One account in the fixed chart of accounts (Bank, AR, inventory classes, WIP, GRNI, AP, Customer Deposits, Revenue, COGS, Production Variance, Conversion Cost Applied, Inventory Adjustment). | A customer's or supplier's record. |
| **Journal entry** | noun | One balanced posting: debits equal credits. Tied to the document that caused it. | |
| **Debit** / **Credit** | noun | The two sides of a journal line. A line is one or the other, never both. | |
| **Reverse** | verb | To cancel a posted journal entry by posting its mirror. The original stays. A reversal cannot be reversed. | To delete. |
| **Source document** | noun | The receipt, shipment, invoice, payment or adjustment that a journal entry posts for. | |
| **Customer invoice** | noun | A bill to a customer: *commercial* (at shipment), *prepayment*, *deposit* or *balance*. | |
| **Supplier invoice** | noun | A supplier's bill, held under Northwood's own number as well as the supplier's. | |
| **Payment** | noun | Money in from a customer or out to a supplier, by bank transfer, cash, card or cheque. | A shipment. |
| **Allocation** | noun | The part of a payment applied to one invoice. | |
| **Paid amount** | noun | The total of an invoice's allocations. A running total, never set by hand. | |
| **GRNI** | noun | Goods received not invoiced: the account that bridges a goods receipt and its supplier invoice. | |
| **Customer Deposits** | noun | The liability that holds up-front money until the goods ship. | Revenue. |
| **Refund** | noun | Up-front money returned to a customer when a sales order ends without shipping. | A credit note (left out). |
| **Production variance** | noun | Actual conversion minus standard conversion for a work order. | |
| **Exchange rate** | noun | The rate from one currency to another on a date. Reference data. | |
| **Tax code** | noun | A named flat tax rate. Reference data. | |

### 3.8 Roles

| Role | Kind | May |
|---|---|---|
| **Sales clerk** | noun | Place and cancel sales orders; manage customers. |
| **Warehouse operator** | noun | Post shipments and goods receipts; see stock. |
| **Warehouse manager** | noun | Post stock adjustments; release reservations by force. |
| **Production planner** | noun | Prioritise work orders. |
| **Production operator** | noun | Complete or skip operations. |
| **Buyer** | noun | Raise purchase requisitions; approve and reject purchase orders; manage suppliers and their prices. |
| **AP clerk** | noun | Record supplier invoices; approve or reject them by hand; post supplier payments. |
| **AR clerk** | noun | Record customer payments; see invoices. |
| **Accountant** | noun | Reverse journal entries; see the financial dashboard; edit standard cost. |
| **Product administrator** | noun | Maintain products, BOMs, reorder policies and valuation classes. |
| **Auditor** | noun | Read everything; change nothing. |

### 3.9 Spent words

Words taken by the platform, a neighbouring system or common English in another sense. Do not use
them in the domain sense shown, or qualify them every time.

| Word | Where it is taken | Use instead |
|---|---|---|
| *Order* (bare) | Three kinds exist. | *Sales order*, *purchase order*, *work order*. |
| *Status* (bare) | Every aggregate has one, each with its own values. | *Order status*, *line status*, *material status*, … |
| *Repository*, *Projection*, *Port*, *Lookup* | Code-structure words. | Not domain words. |
| *Event*, *Message*, *Topic* | Messaging plumbing. | *Domain event* when the fact itself is meant. |
| *Account* | Login accounts in security. | *Account* means a ledger account only. |
| *Transaction* | Database transaction. | *Journal entry* for the ledger; *Saga* for a business process. |
| *Release* | Two domain senses (section 4). | |

---

## 4. Names still open

**Which words are still ambiguous? Five. Each is listed with candidates. None is settled here.**

| Word | The two senses | Candidates | Leaning |
|---|---|---|---|
| **Release** | (a) To issue a work order to the shop floor. (b) To give back reserved stock. | (b) → *free*, *unreserve*, *return to pool* | *Unreserve*: plain, but a coined word. |
| **Fulfil / fulfilment** | (a) A replenishment request is fulfilled (supply landed). (b) Sales-order fulfilment (the whole order-to-cash path). | (a) → *satisfied*, *closed*, *landed* | *Satisfied*: natural for a request, but the event `ReplenishmentFulfilled` would need renaming. |
| **Reserved** | (a) A line or order status. (b) A stock balance. (c) A stock reservation status. (d) A work-order material status. | Keep for (b). (a) → *allocated* | Open. Changing the line vocabulary also changes the order fold. |
| **Cancelled** | A terminal for sales orders, lines, work orders, purchase orders, supplier invoices, replenishment requests and two Sagas. | Keep the word, always with its noun. | Probably acceptable: the sense is the same everywhere (ended before completion). |
| **Status vocabularies on a sales order** | Three exist: line status, order status, and the 360 view's separate status columns. | Unify the 360 view's vocabulary with the order status, or name it differently (*progress*). | Deferred. |

---

## 5. The rules

**Who may do what, and what must always be true? The rules below are grouped by subdomain and use
only glossary words. Rules that select or are reused are also written as specifications (section 10).**

### 5.1 Product

1. A product administrator registers a product with a SKU, name, product type and unit of measure.
   The SKU is unique and never changes.
2. Product type sets the defaults. Raw material: buy, raw-materials class. Semi-finished good: make,
   raw-materials class. Finished good: make, finished-goods class. Service: buy, no class. A product
   administrator may change any default.
3. Make-vs-buy always has at least one flag set.
4. A product may be *to order* only if it is sellable and not a service, and then its reorder policy
   is zero. A service has no replenishment strategy.
5. A change to a sales price or standard cost never changes a document already made.
6. A manufactured product with an active BOM gets its standard cost from the cost roll-up. A change
   to a component's cost rolls up to every parent.
7. A discontinued product cannot be used on a new sales order, purchase requisition, work order or
   stock reservation. Every past fact still refers to it.

### 5.2 Sales

1. A sales clerk places a sales order for one customer, with one or more lines. Quantities are
   positive. Prices are zero or more and are copied from the sales price.
2. The customer's code and name, and the payment terms, are copied onto the sales order when it is
   placed. Later changes to the customer do not reach it.
3. Deposit terms need a deposit percent; other terms must not have one.
4. Fulfilment starts the moment a sales order is placed. **Exception:** if any line's product has a
   planning time fence, stock is not reserved until *requested delivery date − largest fence*. This
   is decided once, when the order is placed.
5. Under prepayment or deposit terms, an up-front invoice is raised when the order is placed, and no
   line ships until it is paid.
6. A sales order may be cancelled or amended (lines added, removed or changed) only while it is not
   at a terminal and **no line has shipped**. This rule reads the lines, never the order status.
7. Cancellation has two phases. A sales clerk requests it. Inventory then decides between the
   cancellation and any shipment for the same order. Whichever comes first wins. If a shipment wins,
   the cancellation outcome is *cancellation rejected*.
8. Requesting cancellation twice has the same effect as once.
9. A sales order is rejected when supply for one of its lines cannot be found: the replenishment
   request was cancelled as undispatchable. A sales order that is already being cancelled is not
   rejected.
10. A sales order is complete when it is fully shipped and fully paid.
11. After shipment, a sales order cannot be cancelled. Undoing a shipment is a return, which is left
    out (section 12).

### 5.3 Inventory

1. Available is always on-hand minus reserved. On-hand never goes below zero.
2. Every change to on-hand is a stock movement. A stock balance is the running total of its
   movements and is never set by hand.
3. A stock reservation holds stock for exactly one sales order or exactly one work order.
4. When a sales-order line or work-order material is short, inventory reserves what it can and
   raises a replenishment request for the rest.
5. A line ships only if it is fully reserved, any prepayment gate is cleared, and it is not
   cancelled or already shipped.
6. Shipping lowers on-hand and reserved by the same amount. Available does not change.
7. A posted goods receipt, shipment or stock adjustment never changes. A correction is a new one.
8. A negative stock adjustment that would take on-hand below zero is refused.
9. **Replenishment is raised by inventory alone**, for one of four reasons:
   - *Reorder-point breach*: on-hand drops below the reorder point (and the point is above zero).
   - *Work-order shortage*: a work order's raw material cannot be fully reserved.
   - *Sales-order shortage*: a to-stock sales-order line cannot be fully reserved.
   - *Order-pegged*: a to-order sales-order line needs its own supply.
10. For reorder-point breaches and work-order shortages, there is **at most one open replenishment
    request per product per warehouse**. A new trigger while one is open does nothing. Sales-order
    shortages and order-pegged requests are one per line.
11. The target comes from make-vs-buy. Manufactured wins when both flags are set. A product with
    neither flag is unsourceable: no request is raised and the fact is logged.
12. Order-pegged supply is held for its line when it lands. It is never in available-to-promise.
13. The pegging stops at the parent. A to-order product's components are supplied by their own
    strategy.

### 5.4 Manufacturing

1. A product has at most one active BOM. Activating a new version retires the old one.
2. A BOM may not contain itself through any chain of sub-assemblies.
3. A work order is released only for a manufacturing-targeted replenishment request, for a product
   that is manufactured and has an active BOM. Otherwise the request is undispatchable.
4. Releasing a work order copies the BOM onto its materials and the routing onto its operations. It
   releases a child work order for every sub-assembly line, and asks inventory to reserve the raw
   materials.
5. A parent work order's operations do not start until all its children are complete.
6. Completed quantity plus scrapped quantity never exceeds planned quantity.
7. A production operator completes or skips operations. A skipped operation is recorded and does not
   block completion.
8. When a parent completes, it consumes its children's output.
9. A work order may be withdrawn (cancelled) only while it is released. Once in progress it refuses.
   Withdrawal happens only as a compensation leg today.

### 5.5 Purchasing

1. A purchase requisition is manual or stock replenishment. A stock-replenishment requisition is
   approved when it is raised. A manual one needs a buyer.
2. Converting a purchase requisition picks the preferred approved vendor and that supplier's
   price for the quantity.
3. A purchase order from a replenishment, with a known price, is sent when it is made. A manual one
   waits at draft for a buyer to approve or reject it.
4. Received and invoiced quantities never exceed ordered quantity. Paid never exceeds invoiced, and
   invoiced never exceeds total.
5. A purchase order may be withdrawn only while it is draft or sent. Once goods are received it
   refuses.
6. Setting a supplier price equal to the current one changes nothing and records nothing.
7. A supplier price is more than zero.

### 5.6 Finance

1. Every economic event posts one journal entry, and **its debits equal its credits**. Nothing can
   post an unbalanced entry.
2. A journal line is a debit or a credit, never both, and never zero.
3. A posted journal entry never changes. It may only be reversed, and a reversal cannot be reversed.
4. An allocation never exceeds what is still owed on its invoice. A payment's allocations never
   exceed the payment.
5. An invoice's paid amount is the total of its allocations, never set by hand.
6. Up-front money goes to Customer Deposits, never Revenue. An up-front invoice posts nothing when it
   is raised. The money posts when it is paid, and moves to Revenue when the goods ship.
7. A supplier invoice is approved when the three-way match holds. If it does not, it waits for an AP
   clerk to approve or reject it by hand. A rejection is final.
8. When a sales order ends without shipping (compensated, compensation failed, or rejected), any
   paid up-front money is refunded, once. Nothing else is reversed, because nothing was recognised.
   The refund follows the confirmed end of the order, never the cancellation request.
9. Postings follow the perpetual-inventory pattern:

| When | Debit | Credit |
|---|---|---|
| Goods received | Inventory (by valuation class) | GRNI |
| Supplier invoice approved | GRNI | AP |
| Supplier paid | AP | Bank |
| Shipment | COGS (by valuation class) | Inventory |
| Commercial invoice raised | AR | Revenue |
| Customer pays a commercial invoice | Bank | AR |
| Customer pays up front | Bank | Customer Deposits |
| Up-front order ships | Customer Deposits | Revenue |
| Refund | Customer Deposits | Bank |
| Raw materials issued to a work order | WIP | Raw-materials inventory |
| Conversion applied | WIP | Conversion Cost Applied |
| Sub-assemblies consumed | Parent WIP | Finished-goods inventory |
| Work order complete (full standard cost) | Finished-goods inventory | WIP |
| Efficiency variance | Production Variance or WIP | WIP or Production Variance |

WIP nets to zero for each work order.

### 5.7 Deltas get aggregates, totals get views

A concept gets an aggregate only when it records its own change: a fact with identity, a lifecycle
and someone downstream who cares. **A running total of such facts, or a copy of facts owned
elsewhere, is never an aggregate.** Stock balance, WIP balance, an invoice's paid amount and an
account balance are totals. Product cards are copies. Making a total into an aggregate would let it
disagree with the facts that made it. Double-entry bookkeeping exists to prevent exactly that.

---

## 6. Aggregates

**What are the consistency boundaries? Eighteen aggregates across six subdomains. Each is changed
whole, reached only through its root, and refers to others only by identity.** Every aggregate has
its own status, even when it has only one value today.

### 6.1 Product

**Product**: root `Product`.
- *Contains:* SKU, name, product type, unit of measure (by identity), make-vs-buy, sellable and
  stocked flags, sales price, standard cost, reorder policy, replenishment strategy, valuation class,
  planning time fence, active BOM (by identity), approved vendors, status (active, inactive,
  discontinued).
- *Guards:* product rules 1–4 and 7.
- *Refers to:* BOM, Supplier (by identity).

### 6.2 Sales

**Customer**: root `Customer`.
- *Contains:* code, name, contact, billing address, shipping address, default payment terms, status
  (active, inactive, blocked).
- *Guards:* a unique code.

**Sales order**: root `SalesOrder`, child entity `SalesOrderLine`.
- *Contains:* number, customer (by identity, with code and name copied), dates, payment terms,
  deposit percent, currency, totals, the time a cancellation was requested, order status, and lines.
  Each line has a number, product (by identity, with SKU and name copied), ordered, reserved and
  shipped quantities, price, tax, and line status.
- *Order status* is the faithful fold of the line statuses:
  `open ⊏ partially reserved ⊏ reserved ⊏ partially shipped ⊏ shipped`, plus three terminals the fold
  never overrides: **completed**, **cancelled**, **rejected**. The sales order is the only thing that
  sets its order status.
- *Guards:* sales rules 1–3 and 6–10. Reserved and shipped never exceed ordered.
- *Refers to:* Customer, Product.

### 6.3 Inventory

**Stock reservation**: root `StockReservation`, child entity `StockReservationLine`.
- *Contains:* the one sales order or work order it serves, warehouse, status (reserved, partially
  reserved, failed, released), lines with requested, reserved and shortage quantities.
- *Guards:* exactly one source.

**Goods receipt**: root `GoodsReceipt`, child `GoodsReceiptLine`. Against one purchase order.
Status: posted. Never changed once posted.

**Shipment**: root `Shipment`, child `ShipmentLine`. Against one sales order, with the customer
copied. Status: posted. Never changed once posted.

**Stock adjustment**: root `StockAdjustment`, single line. Warehouse, product, direction (in, out),
reason. Status: posted. Never changed once posted.

**Replenishment request**: root `ReplenishmentRequest`.
- *Contains:* product, warehouse, requested quantity, target, reason, status
  (`requested → dispatched → fulfilled`, or `cancelled`), what it was dispatched as (work order or
  purchase requisition, by identity), the linked purchase order, and, for sales-order shortage and
  order-pegged requests, the originating sales order and line.
- *Guards:* inventory rule 10 (at most one open request). A purchase order can be linked only once
  the request is dispatched.
- *Refers to:* Product, Work order or Purchase requisition, Purchase order, Sales order.

### 6.4 Manufacturing

**BOM**: root `Bom`, child entity `BomLine`.
- *Contains:* finished product (by identity, copied SKU and name), version, status (draft, active,
  inactive), lines with component, component kind, quantity per finished unit and scrap factor.
- *Guards:* manufacturing rules 1 and 2.

**Work order**: root `WorkOrder`, children `WorkOrderMaterial` and `WorkOrderOperation`.
- *Contains:* number, product, BOM (by identity), parent work order (by identity, for a child),
  originating replenishment request and sales order (by identity, when there is one), planned,
  completed and scrapped quantities, status (released, in progress, completed, closed, cancelled),
  material status, priority, planned and actual dates.
- *Guards:* manufacturing rules 5, 6 and 9.

**Routing** (`Routing`, `RoutingOperation`) is a template, not yet an aggregate. Nothing changes it
inside the domain today (section 13).

### 6.5 Purchasing

**Supplier**: root `Supplier`. Code, name, contact, address, status (active, inactive, blocked).
Unique code.

**Supplier product price**: root `SupplierProductPrice`. Supplier and product (by identity),
currency, unit price, effective dates, minimum quantity. Guards purchasing rules 6 and 7.

**Purchase requisition**: root `PurchaseRequisition`, child `PurchaseRequisitionLine`.
- *Contains:* number, source type (manual, stock replenishment), the source (product or replenishment
  request), status (approved, rejected, converted), lines.
- *Guards:* the source matches the source type.

**Purchase order**: root `PurchaseOrder`, child `PurchaseOrderLine`.
- *Contains:* number, supplier (by identity), purchase requisition (by identity), currency, dates,
  totals (subtotal, tax, total, received, invoiced, paid), status (draft, sent, partially received,
  received, partially invoiced, invoiced, paid, cancelled), lines.
- *Guards:* purchasing rules 4 and 5.

### 6.6 Finance

**Customer invoice**: root `CustomerInvoice`, child `CustomerInvoiceLine`. Sales order (by
identity), invoice type, customer copied, dates, totals, paid amount, status (posted, partially
paid, paid). Lines never change once posted. Paid amount never exceeds total.

**Supplier invoice**: root `SupplierInvoice`, child `SupplierInvoiceLine`. Northwood's number and the
supplier's, purchase order and goods receipt (by identity), status (three-way match failed,
approved, partially paid, paid, cancelled), match status (matched, variance, failed).

**Payment**: root `Payment`, with `PaymentAllocation` values inside.
- *Contains:* number, direction (incoming, outgoing), type (customer payment, supplier payment), the
  customer or the supplier (one only, matching the type), method, amount, amount allocated, status,
  allocations.
- *Guards:* finance rule 4.

**Journal entry**: root `JournalEntry`, child `JournalEntryLine`.
- *Contains:* number, posting date, source subdomain, source document type and identity, status
  (draft, posted, reversed), currency, lines.
- *Guards:* finance rules 1–3.
- Written once, and emits no domain event. It is kept for audit.

### 6.7 Sagas

A Saga is not an aggregate of any subdomain, but it has state and a lifecycle, so it is listed here.
The states are in section 9.2.

| Saga | Lives in | Keyed by |
|---|---|---|
| `SalesOrderFulfilmentSaga` | Sales | one sales order |
| `WorkOrderSaga` | Manufacturing | one work order |
| `PurchaseToPaySaga` | Purchasing | one purchase order |

---

## 7. Value objects

**Which concepts are values, and when is a value valid? Everything without its own identity and
lifecycle. A value that exists is valid.**

| Value | Valid when |
|---|---|
| `Money` | Has an amount and a currency. Arithmetic needs the same currency. |
| `Quantity` | Has an amount and a unit. Arithmetic needs the same unit. |
| `Sku` | Matches the SKU pattern (section 3.1). |
| `PaymentTerms` | One of on shipment, prepayment, cash on delivery, deposit. |
| `ProductType`, `ReplenishmentStrategy`, `ValuationClass` | One of their listed values. |
| `ApprovedVendor` | Has a supplier. At most one per product is preferred. |
| Reorder policy | Point and quantity are both zero or more. |
| `StockMovementType`, `StockMovementDirection`, `StockTrackingMode` | One of their listed values. |
| `PaymentAllocation` | Points to exactly one invoice (customer or supplier). The amount is more than zero. Status is posted or reversed. |
| `JournalEntryLine` | One account. Exactly one of debit or credit is more than zero. |
| Tax application | A tax code and its rate at the time. |
| Address, contact | Held as values on customers and suppliers. |
| Every *Status*, *Type*, *Kind*, *Reason* and *Method* | One of the listed values for its aggregate. |
| Identities (`SalesOrderId`, `WorkOrderId`, …) | Not empty. Each aggregate has its own type. |

`Address`, contact, and tax application are local to the subdomains that use them. They are not
shared, because no two subdomains use them the same way yet.

---

## 8. Domain events

**What facts does the domain record? Every change that someone else needs to hear about, named in
the past tense. Together they are the history of the business.** Each event is owned by the
subdomain that names it.

### 8.1 Product

`ProductCreated`, `SalesPriceChanged`, `StandardCostChanged`, `ReorderPolicyChanged`,
`ReplenishmentStrategyChanged`, `ValuationClassChanged`, `MakeVsBuyChanged`,
`PlanningTimeFenceChanged`, `ActiveBomChanged`, `ApprovedVendorListChanged`, `ProductDiscontinued`.

Each change has its own fact, so a consumer listens only to what it needs. There is no reactivation
event.

### 8.2 Sales

- *Customer:* `CustomerRegistered`, `CustomerNameChanged`, `CustomerContactChanged`,
  `CustomerAddressChanged`, `CustomerDeactivated`.
- *Sales order:* `SalesOrderPlaced`, `SalesOrderLineAdded`, `SalesOrderLineRemoved`,
  `SalesOrderLineQuantityChanged`, `StockReservationRequested`, `PrepaymentInvoiceRequested`,
  `DepositInvoiceRequested`, `SalesOrderUpfrontPaymentSettled`, `SalesOrderReadyToShip`,
  `SalesOrderShipped`.
- *Endings:* `SalesOrderCancellationRequested`, `SalesOrderCompensated`,
  `SalesOrderCompensationFailed` (an escalation: some supply could not be withdrawn),
  `SalesOrderRejected`.

### 8.3 Inventory

`StockReserved`, `RawMaterialsReserved`, `SalesOrderLineReservationChanged`, `GoodsReceived`,
`ShipmentPosted`, `StockAdjusted`, `ReplenishmentRequested`, `ReplenishmentFulfilled`,
`ReplenishmentCancelled`, `InventorySalesOrderCancellationApplied` (the cancellation won, with the
compensation legs still to acknowledge), `OrderPeggedSupplyCancellationRequested` (one per leg, to
manufacturing or purchasing).

### 8.4 Manufacturing

`BomActivated`, `WorkOrderCreated` (a work order is created released), `RawMaterialReservationRequested`,
`RawMaterialShortageDetected`, `OperationCompleted`, `WorkOrderConversionApplied`,
`SubAssembliesConsumed`, `WorkOrderManufacturingCompleted`, `ProductMaterialsCostComputed`,
`WorkOrderPriorityChanged`, `WorkOrderCancelled`, `WorkOrderCancellationApplied`,
`ReplenishmentDispatched`, `ReplenishmentUndispatchable`.

### 8.5 Purchasing

`SupplierRegistered`, `SupplierStatusChanged`, `SupplierDetailsChanged`,
`SupplierProductPriceChanged`, `PurchaseRequisitionCreated`, `PurchaseOrderCreated`,
`PurchaseOrderApproved`, `PurchaseOrderCancelled`, `PurchaseOrderCancellationApplied`,
`ReplenishmentDispatched`, `ReplenishmentUndispatchable`.

`ReplenishmentDispatched` and `ReplenishmentUndispatchable` are named the same in Manufacturing and
Purchasing because they state the same fact: *this target accepted, or could not accept, the
request*. Each subdomain owns its own.

### 8.6 Finance

`CustomerInvoiceCreated`, `CustomerPaymentReceived`, `SupplierInvoiceApproved`,
`SupplierInvoiceRejected`, `SupplierPaymentMade`.

A small set on purpose: these are the facts other subdomains wait on. Paid amounts and invoice
statuses follow from allocations and are not announced separately. Journal entries announce nothing.

---

## 9. Domain services, policies and Sagas

**What work belongs to no single aggregate? Replenishment detection, BOM explosion, cost roll-up,
three-way match, posting, refund, and the three Sagas that carry a business process across
subdomains.**

### 9.1 Services and policies

| Service or policy | Subdomain | What it does |
|---|---|---|
| **Replenishment detection** (`ReplenishmentDetectionService`) | Inventory | After every drop in on-hand, and on every shortage, decides whether a replenishment request is due, picks the target from make-vs-buy, and raises it, keeping inventory rules 9–11. |
| **Cancellation arbitration** | Inventory | Decides whether a cancellation or a shipment wins for a sales order, and lists the compensation legs: one per *dispatched* order-pegged request. To-stock supply, requests not yet dispatched, and supply already received give no leg. |
| **Work-order release** (`WorkOrderReleaseService`) | Manufacturing | BOM explosion: copies the BOM and routing onto the work order and releases child work orders for sub-assemblies. |
| **BOM cycle detection** (`BomCycleDetector`) | Manufacturing | Refuses a BOM that contains itself. |
| **Operation completion** (`WorkOrderOperationService`) | Manufacturing | Completes operations, applies conversion cost, consumes children, and completes the work order. |
| **Cost roll-up** (`MaterialsCostRollupService`) | Manufacturing | Computes standard cost through the BOM, and re-computes every parent when a component's cost changes. |
| **Prioritisation** (`WorkOrderPrioritisationService`) | Manufacturing | Sets a work order's priority. |
| **Three-way match** (in `SupplierInvoiceService`) | Finance | Matches a supplier invoice against its purchase order line and goods receipt. |
| **Posting** (`JournalEntryService`) | Finance | Turns each source document into its balanced journal entry (finance rule 9), and reverses by source document. |
| **Cancellation refund** (`CustomerCancellationRefundService`) | Finance | Refunds paid up-front money, once, when a sales order ends without shipping. |
| **Currency conversion** (`CurrencyConverter`) | Finance | Converts money at a dated rate. Only same-currency conversion is used today. |

### 9.2 Sagas

**Sales-order fulfilment** (`SalesOrderFulfilmentSaga`). Its states are only the points where it
decides something. Shipping, invoicing and payment progress show on the lines and in the 360 view.

| State | Meaning |
|---|---|
| started | The sales order is placed. |
| awaiting release | Waiting for the planning time fence to open. |
| awaiting prepayment | Waiting for the up-front invoice to be paid. |
| prepaid | The up-front invoice is paid. |
| stock reservation requested | Inventory has been asked for stock. |
| stock reservation incomplete | Some lines are short. Waiting for each one's replenishment. |
| supply secured | Every line is reserved. Waiting until the order is both shipped and settled. |
| completed | Terminal. Shipped and paid. |
| rejected | Terminal. A line cannot be supplied. |
| compensating | The cancellation won. Waiting for each compensation leg's answer. |
| compensated | Terminal. Every leg was undone. |
| compensation failed | Terminal. At least one leg refused. The order is still cancelled. Someone must deal with the supply that remains. |
| failed | Terminal. Something broke that is not a business outcome. |

With no compensation legs, the Saga goes straight from the cancellation to *compensated*.

**Work order** (`WorkOrderSaga`): `work order created → raw-material reservation requested →
raw materials reserved | raw-material shortage → completed`, with terminals *failed* and *cancelled*.
It is the same for every work order, whether or not a sales order is behind it.

**Purchase-to-pay** (`PurchaseToPaySaga`): `started → purchase order approved → waiting for goods →
goods received → supplier invoice approved → (supplier partially paid) → completed`, with terminals
*failed* (the invoice was rejected) and *cancelled* (the purchase order was rejected or withdrawn).

---

## 10. Specifications

**Which rules are combined, reused, or used both to check and to select? These ten. Each is named
once here and used under that name.**

| Specification | True when | Used to |
|---|---|---|
| **Changeable sales order** | Not at a terminal, and no line has shipped. | Allow cancellation; allow amending lines. Both use the same rule, so they cannot differ. |
| **Shippable line** | Fully reserved, prepayment gate clear, not cancelled, not already shipped. | Allow a shipment; mark the order ready to ship. |
| **Open replenishment request** | Status is requested or dispatched. | Select for the one-open rule; select for compensation legs. |
| **One-open reason** | Reason is reorder-point breach or work-order shortage. | Decide whether the one-open rule applies. |
| **Reorder-point breach** | Reorder point above zero, and on-hand below it. | Raise a replenishment request. |
| **Unsourceable product** | Neither manufactured nor purchased. | Skip replenishment and log. |
| **To-order eligible product** | Sellable and not a service. | Allow the to-order strategy. |
| **Withdrawable purchase order** | Draft or sent. | Accept or refuse a compensation leg. |
| **Withdrawable work order** | Released. | Accept or refuse a compensation leg. |
| **Three-way match** | Quantity equals received, and price is within tolerance. | Approve a supplier invoice, or send it to manual review. |

---

## 11. Views

**What does the business need to read that no single aggregate holds? Eight views and three kinds of
running total. None of them accepts changes, and each can be rebuilt from the events.**

| View | One row per | Shows |
|---|---|---|
| **Sales order 360** | sales order | Everything about one order: customer, lines, stock, manufacturing, shipments, invoices, payments. It has its own status columns, separate from the order status (section 4). |
| **Available-to-promise** | product | On-hand, reserved, available, plus open purchase orders and work orders adding stock and open sales orders using it. Order-pegged supply is left out. |
| **Production planning board** | work order | Header, materials, operations, status, priority, parent and children. |
| **Material shortages** | short material | Open shortages and the purchase requisition or order that will close each one. |
| **Purchase order tracking** | purchase order | Approval, receipt, invoice and payment status. |
| **Replenishment history** | replenishment request | Each request's reason, target, quantity, status and linked work order or purchase order. |
| **Customer dashboard** | customer | Status of the customer's orders. |
| **Financial dashboard** | day and currency | Inventory value, AR, AP, WIP value. |

Running totals: **stock balance** and **WIP balance** (Inventory), **paid amount** on invoices and
**account balances** (Finance). **Product cards** are copies, kept by every subdomain.

---

## 12. Left out

**What does the domain deliberately not cover yet? The things below. Each is a known gap, not an
oversight.**

- **Returns**: customer returns, supplier returns, credit notes. Undoing a shipment is a new forward
  process, not compensation.
- **The escalation after compensation fails**: returning received goods, or scrapping WIP with a
  loss posted.
- **Operator cancellation of a work order**: halting one in progress, or letting it finish and
  scrapping it.
- **Pricing**: price lists, customer prices, discounts, promotions.
- **Time-phased MRP**: soft versus hard reservation, and lead-time backward scheduling.
- **Finite-capacity scheduling.**
- **Multi-warehouse fulfilment**: every flow uses one warehouse.
- **Multi-currency**: only AUD is used. Rates exist but are not applied when posting.
- **Tax**: one flat rate per tax code, one jurisdiction, no separate tax account.
- **Identity**: users and roles are not a subdomain. Roles gate actions; they are not modelled.
- **Supplier selection** beyond the preferred approved vendor and its price.

---

## 13. Open questions

**What is still to decide, and where does the code disagree with this document? The items below.
Each says where the fix belongs.**

1. **Compensation that never gets an answer.** A Saga in *compensating* waits forever if a leg never
   answers. Should it time out and ask again? *(Domain decision first.)*
2. **Routing is not an aggregate.** It has no commands and no events. Should routing authoring become
   part of the domain, with its own lifecycle like a BOM? *(Domain.)*
3. **The open names in section 4**: *release*, *fulfil*, *reserved*, and the third status vocabulary on
   the 360 view. *(Domain, then code.)*
4. **Domain services live outside the domain layer.** Replenishment detection, work-order release,
   cost roll-up, three-way match, posting and refund are domain services in this document, but the
   code holds them as application services, and the three-way match has no name of its own.
   *(Code: decide whether to move them, or record the placement in the architecture.)*
5. **Purchase-order status has no *approved*.** `PurchaseOrderApproved` is announced, but approving
   moves a draft straight to *sent*. Is *approved* a state the business needs? *(Domain.)*
6. **Work-order shortage on the purchasing side.** The purchase-requisition source *work-order
   shortage* was removed. The replenishment reason of the same name is still used. Check that no
   lower document still uses the old source. *(Lower documents.)*
7. **Inventory documents with one status.** Goods receipt, shipment and stock adjustment have only
   *posted*. That is on purpose, to leave room for reversal. Should reversal be modelled? *(Domain.)*
8. **An unsourceable product is only logged.** Should a product with neither make-vs-buy flag be
   refused when it is set up, instead? *(Domain.)*
