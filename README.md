# Northwood ERP

A microservices showcase for **CQRS**, **saga orchestration**, and the **transactional outbox/inbox** pattern, structured around a small ERP domain (sales, inventory, manufacturing, purchasing, finance, reporting). Plus a React demo UI that makes it watchable for an audience.

Underneath the buzzwords it's one architectural idea applied uniformly: every service is a domain-specific journal whose facts are events, with running totals as derived projections — **Pacioli's 1494 double-entry discipline generalised to non-monetary domains** (inventory keeps the books on physical units, manufacturing on WIP and labour, sales on customer commitments). The deepest framework here isn't Spring; it's Pacioli. See [`docs/architecture.md`](docs/architecture.md) → *Why this codebase looks the way it does — ERP as applied accounting epistemology* for the full framing.

This README is a 30-second orientation. Every link below points at the doc that actually answers the corresponding question.

## What's here

```
Northwood/
├── pom.xml                       Parent POM
├── docker-compose.yml            Postgres 17 + Kafka 4.1.2 (KRaft, single broker)
├── db/northwood_erp.sql          Baseline schema + seed data
│
├── shared-kernel/                Pure Java value objects (Money, Quantity, Sku, …)
├── shared/                       Outbox/inbox base, EventEnvelope, Kafka publisher, saga base (split: `shared.application.*` ports, `shared.infrastructure.*` adapters, `shared.api.*` audit REST)
│
├── product-service/              SKUs, pricing, reorder policy (Material Master / Shape A hub)
├── sales-service/                Sales orders + sales_order_fulfilment_saga
├── inventory-service/            Stock balances, reservations, goods receipts, shipments
├── manufacturing-service/        Work orders, BOMs, routing + make_to_order_saga
├── purchasing-service/           POs, requisitions, supplier prices + purchase_to_pay_saga
├── finance-service/              AP/AR invoices, payments, journal entries (perpetual inventory)
├── reporting-service/            Six read-side projections, inbox-only
├── demo-web-ui-bff/              BFF for the technical demo SPA (port 8080)
├── demo-web-ui/                  React + Vite SPA for the technical demo (port 5173)
├── erp-web-ui-bff/               BFF for the operational ERP SPA (port 8089)
│
└── erp-web-ui/                   React + Vite SPA — operational ERP (port 5174)
```

12 Maven modules + two SPAs. Every Java service has full DDD layering (`domain` / `application` / `infrastructure` / `api`); all three sagas drive end-to-end; reporting projects six cross-context views.

## Stack

- **Java 21**, Spring Boot 4.0.5 (Spring Framework 7, Jakarta EE 11), Maven multi-module
- **PostgreSQL 17** with schema-per-service in one DB (`search_path = <service>, shared` per connection)
- **Liquibase** for migrations (manually wired — Spring Boot 4 doesn't ship the auto-config)
- **Spring Data JDBC** (chosen over JPA for explicit aggregate boundaries)
- **Kafka 4.1.2** (KRaft, single broker) — wire format JSON via Jackson 3
- **Testcontainers** for the integration-test seam
- **React 18 + Vite + Tailwind v4** for the demo SPA; **TanStack Query** + a small scenario runner

## Run it

The full nine-terminal walkthrough — Postgres, Kafka, seven services, BFF, SPA, with the kafka profile per service — lives in **`docs/demo-script.md`**. Quick smoke:

```powershell
docker compose up -d                        # postgres + kafka + keycloak
mvn install -DskipTests
$env:SPRING_PROFILES_ACTIVE = "kafka"
mvn -pl product-service spring-boot:run     # one service in one terminal
```

For the audience-facing demo, follow `docs/demo-script.md` § "Bringing the stack up" and click **🎬 Scenarios → 7.1** at `http://localhost:5173/saga-console`.

## Where to read next

| If you want to… | Open |
|---|---|
| Run a demo end-to-end | `docs/demo-script.md` |
| Understand the architecture before changing code | `CLAUDE.md` |
| See the technical demo SPA's design rationale | `docs/demo-web-ui-design.md` |
| See the operational ERP SPA's design rationale | `docs/erp-web-ui-design.md` |
| Browse persona-driven stories with status flags | `docs/user-stories.md` |
| Pick the next thing to build | `docs/dev-todo.md` (priority-ordered backlog) |
| See what's been shipped | `docs/dev-done.md` (append-only changelog) |
| Read defects surfaced by tests | `docs/bugs-caught-by-tests.md` |
| Per-SPA running notes | `demo-web-ui/README.md`, `erp-web-ui/README.md` |

## Tests

```powershell
mvn test                                       # all 621 backend unit tests
mvn -pl inventory-service verify               # Testcontainers seam IT (~50s)
cd demo-web-ui ; npm.cmd run build ; cd ..     # technical demo SPA typecheck + bundle
cd erp-web-ui ; npm.cmd run build ; cd ..      # operational ERP SPA typecheck + bundle
```

## Tear down

```powershell
docker compose down -v        # wipe Postgres + Kafka volumes for a clean slate
```
