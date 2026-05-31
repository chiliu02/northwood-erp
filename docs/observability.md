# Observability — traces, metrics, logs

How to run and use the Northwood observability tier (§1D). The stack is the **LGTM** quartet — **L**oki (logs), **G**rafana (UI), **T**empo (traces), **M**imir-equivalent Prometheus (metrics) — running as docker-compose sidecars alongside Postgres/Kafka/Keycloak, with every Spring service instrumented to push to it.

The design goal: a single business transaction (place a sales order) produces a **distributed trace** spanning sales → inventory → finance, **RED metrics** scraped from each service, and **structured logs** carrying the same `traceId` — and Grafana lets you pivot between all three from one click. The worked example at the bottom walks the Sales-Order-fulfilment Saga end to end.

Companion docs: `docs/sagas.md` (the saga state machines), `docs/messaging.md` (outbox → Kafka → inbox), `docs/demo-script.md` (full runbook).

---

## What's in the stack

| Concern | Backend | Image | Port(s) | Spring → backend path |
|---|---|---|---|---|
| Metrics | Prometheus | `prom/prometheus:v3.0.1` | 9090 | Prometheus **scrapes** each service's `/actuator/prometheus` |
| Traces | Tempo | `grafana/tempo:2.7.0` | 3200 (query), 4317 (OTLP gRPC), 4318 (OTLP HTTP) | Service **pushes** OTLP → `OTLP_ENDPOINT` (`:4317`) |
| Logs | Loki | `grafana/loki:3.3.0` | 3100 | Logback `Loki4jAppender` **pushes** → `LOKI_URL` (`:3100/loki/api/v1/push`) |
| Logs (container) | Promtail | `grafana/promtail:3.3.0` | — | Tails the Docker socket → Loki (infra containers) |
| UI | Grafana | `grafana/grafana:11.3.1` | 3000 | Queries all three datasources |

All five are defined in `docker-compose.yml`; their backend configs live under `db/{prometheus,tempo,loki,promtail,grafana}/`. Grafana is provisioned with three datasources + one dashboard (`db/grafana/provisioning/`, `db/grafana/dashboards/northwood-overview.json`) — no manual setup.

### How instrumentation is wired

All observability dependencies are centralized in `shared/pom.xml` so every service inherits them:

- `micrometer-registry-prometheus` — exposes `/actuator/prometheus`.
- `spring-boot-starter-opentelemetry` — the Boot 4 tracer starter. **This is load-bearing**: Boot 4 split tracer auto-config out of actuator, so without this starter the `io.micrometer.tracing.Tracer` bean is never published and the outbox-publisher / saga-adapter wiring fails to start. It transitively pulls the OTLP exporter + micrometer-tracing OTel bridge.
- `loki-logback-appender` (loki4j) + `logstash-logback-encoder` — structured push to Loki.

Per-service `application.yml` (identical block in all 7 services + both BFFs):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus   # prometheus added in §1D.1
  tracing:
    sampling:
      probability: ${NORTHWOOD_TRACING_SAMPLING:1.0}  # 1.0 = capture every trace (demo); 0.1 for prod-style
  otlp:
    metrics:
      export:
        enabled: false                                # metrics path is Prometheus scrape, NOT OTLP push (see note)
    tracing:
      endpoint: ${OTLP_ENDPOINT:http://localhost:4317}
      transport: grpc                               # :4317 is Tempo's OTLP gRPC port; Boot's span exporter defaults to HTTP, so this must be set explicitly (see note)
```

> **Why `tracing.transport: grpc`.** Boot's OTLP **span** exporter defaults to **HTTP** transport, whose default endpoint is `http://localhost:4318/v1/traces`. With `OTLP_ENDPOINT` pinned at `:4317` (Tempo's gRPC receiver) but transport left at its HTTP default, every span is POSTed over HTTP to a port that only speaks gRPC and is silently dropped — Tempo receives nothing while logs (a separate Loki push path) keep flowing. Setting `transport: grpc` makes the exporter match the `:4317` gRPC receiver. (Alternatively, point `endpoint` at `http://localhost:4318/v1/traces` and drop `transport` — but that changes the meaning of `OTLP_ENDPOINT` everywhere, including the AWS wiring, so we pin gRPC instead.)

> **Why `otlp.metrics.export.enabled: false`.** `spring-boot-starter-opentelemetry` transitively pulls `micrometer-registry-otlp`, which Boot auto-configures to **push** metrics to `http://localhost:4318/v1/metrics` by default. In this stack metrics travel the other way — Prometheus **scrapes** `/actuator/prometheus` — and `:4318` is Tempo's OTLP HTTP receiver, which serves `/v1/traces` only. Left enabled, the OTLP meter registry logs `404 page not found` on every publish interval. Disabling it keeps OTLP for **tracing** only; metrics stay on the scrape path.

Logging is in `shared/src/main/resources/logback-spring.xml` — a `CONSOLE` appender (human-readable, with `traceId`/`spanId` from MDC) and a `LOKI` appender (low-cardinality labels `service` + `level`; `traceId`/`spanId` go in the message line so Loki's index doesn't explode).

### Telemetry env vars

| Var | Default | Meaning |
|---|---|---|
| `OTLP_ENDPOINT` | `http://localhost:4317` | Tempo OTLP gRPC receiver |
| `LOKI_URL` | `http://localhost:3100/loki/api/v1/push` | Loki push endpoint (read by logback) |
| `NORTHWOOD_TRACING_SAMPLING` | `1.0` | Trace sample rate. Keep `1.0` locally for complete demo traces; set `0.1` to mimic prod. |

Locally the defaults Just Work because the compose ports are published to `localhost`. On AWS the deployment injects the observability box's private DNS into these vars (see *AWS* below).

---

## Running it

The LGTM services are part of the base `docker-compose.yml`, so the normal bring-up already starts them:

```powershell
# from repo root — Postgres + Kafka + Keycloak + the LGTM stack
docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d
```

Then start the Spring services with the Kafka profile as usual (cross-service events — and therefore cross-service traces — only flow under `kafka`; see `docs/demo-script.md`):

```powershell
$env:SPRING_PROFILES_ACTIVE = "kafka"
mvn -pl sales-service spring-boot:run     # repeat per service, one terminal each
```

The services push traces/logs to the running containers and Prometheus scrapes them at `host.docker.internal:808x`. No service-side flag is needed — the env-var defaults point at the published compose ports.

**Open Grafana:** <http://localhost:3000> — anonymous Admin is enabled (no login). The provisioned **Northwood ERP overview** dashboard has three rows: *Service health* (`up{job="northwood-services"}` + JVM heap), *Bus health*, and *a placed-order's journey*.

Direct backend UIs if you want them: Prometheus <http://localhost:9090>, Tempo (via Grafana Explore), Loki (via Grafana Explore).

**Sanity checks:**

```powershell
# A service is exporting metrics:
curl http://localhost:8082/actuator/prometheus | Select-String http_server_requests

# Prometheus sees all services as up (should list 7 services + 2 BFFs):
# open http://localhost:9090/targets

# Tempo received traces — open Grafana → Explore → Tempo → Search.
```

---

## Using each pillar

### Metrics (Prometheus)

Each service exposes Micrometer metrics at `/actuator/prometheus`; Prometheus scrapes them with a `service` label per target (`db/prometheus/prometheus.yml`). Useful queries in Grafana Explore → Prometheus:

```promql
# Request rate per service (RED — rate)
sum by (service) (rate(http_server_requests_seconds_count[1m]))

# p95 latency for the place-order endpoint
histogram_quantile(0.95,
  sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/sales-orders"}[5m])))

# Error rate (RED — errors)
sum by (service) (rate(http_server_requests_seconds_count{status=~"5.."}[1m]))

# Are all services up?
sum by (service) (up{job="northwood-services"})
```

JVM/process metrics (`jvm_memory_used_bytes`, `process_cpu_usage`, etc.) come for free from the actuator binders. Kafka client metrics surface under `kafka_consumer_*` / `kafka_producer_*` on services running the messaging profile.

### Traces (Tempo)

Every inbound HTTP request and outbound OTLP-instrumented call gets a span; the trace context propagates across the outbox → Kafka → inbox hop, so a trace started in sales continues in inventory and finance. In Grafana Explore → **Tempo**:

- **Search** by service name (`sales-service`), span name (`POST /api/sales-orders`), or duration.
- **TraceQL** for targeted queries, e.g. `{ .service.name = "sales-service" && name =~ "POST /api/sales-orders" }`.
- Click a trace to see the waterfall across services. The Tempo datasource is wired with **trace → logs** (`tracesToLogsV2` → Loki) and **trace → metrics**, so from any span you can jump to the correlated log lines or the service's RED metrics.

### Logs (Loki)

Structured logs are pushed straight from each JVM via the loki4j appender, plus Promtail tails infra container logs. In Grafana Explore → **Loki**:

```logql
# All logs from one service
{service="sales-service"}

# Errors across the bus
{level="ERROR"}

# Follow one transaction across services by trace id (see below)
{service=~".+"} |= "traceId=3a1f...c9"
```

The Loki datasource defines a **derived field** with regex `traceId=([a-f0-9]+)` (`db/grafana/provisioning/datasources/datasources.yaml`), turning every `traceId=…` in a log line into a clickable link straight to that trace in Tempo. That's the logs → traces direction; `tracesToLogsV2` is the reverse. The console appender prints the same `[traceId=… spanId=…]` so you can correlate against terminal output too.

---

## Worked example — Sales-Order fulfilment

This is the showcase: one `POST /api/sales-orders` fans out into a multi-service Saga, and you can watch the whole thing as a single correlated trace + log stream.

### Trigger it

Place a stock-covered order against the seeded customer/product (`docs/demo-script.md` § Demo 3.1):

```bash
curl -X POST http://localhost:8082/api/sales-orders \
  -H 'content-type: application/json' \
  -d '{
    "orderNumber":"SO-DEMO-3-1",
    "customerCode":"CUST-001",
    "currencyCode":"AUD",
    "lines":[{
      "productId":"00000000-0000-7000-8000-000000000001",
      "productSku":"FG-TABLE-001",
      "productName":"Wooden Dining Table",
      "orderedQuantity":1,
      "unitPrice":320
    }]
  }'
```

(In the demo SPA this is **Scenarios → 3.1**; the BFF stamps the auth-bypass header so you don't need a token.)

### What it produces — the happy-path event chain

The Sales-Order-fulfilment Saga (`sales-service/.../saga/SalesOrderFulfilmentSagaManager.java`; states in `docs/sagas.md`) drives:

| # | Event (`EVENT_TYPE`) | Emitter → consumer | Saga state after |
|---|---|---|---|
| 1 | `SalesOrderPlaced` (`sales.SalesOrderPlaced`) | sales → inventory, finance, reporting | `started` |
| 2 | `StockReservationRequested` (`sales.StockReservationRequested`) | sales saga → inventory | `stock_reservation_requested` |
| 3 | `StockReserved` (`inventory.StockReserved`) | inventory → sales | `ready_to_ship` |
| 4 | `ShipmentPosted` (`inventory.ShipmentPosted`) | inventory → sales, finance | `goods_shipped` |
| 5 | `SalesOrderShipped` (`sales.SalesOrderShipped`) | sales → finance | `goods_shipped` |
| 6 | `CustomerInvoiceCreated` (`finance.CustomerInvoiceCreated`) | finance → sales | `invoice_created` |
| 7 | `CustomerPaymentReceived` (`finance.CustomerPaymentReceived`) | finance → sales | `completed` |

Services and ports: sales **8082**, inventory **8083**, finance **8086**, reporting **8087** (read model). Manufacturing/purchasing only join on a stock shortage (the replenishment branch — `docs/sagas.md`).

### Observe it across the three pillars

**1. Trace (Tempo).** Open Grafana → Explore → Tempo → Search for `sales-service`, span `POST /api/sales-orders`. The waterfall shows the command span in sales, then — because trace context rides the outbox → Kafka → inbox hop — child spans appearing in inventory (reserve stock, post shipment) and finance (create invoice, record payment) as each event is consumed. One trace id, the entire fulfilment.

**2. Logs (Loki).** Copy the `traceId` from the trace (or from the sales terminal's `[traceId=…]` line) and run:

```logql
{service=~".+"} |= "traceId=<paste-trace-id>"
```

You get an interleaved, time-ordered log of the same transaction across sales/inventory/finance — saga-state transitions, the reservation, the shipment, the invoice, the payment. Every line links back to the trace via the derived field.

**3. Metrics (Prometheus).** The order's HTTP call shows up in `http_server_requests_seconds_count{service="sales", uri="/api/sales-orders"}`; the downstream Kafka consume/produce rates on inventory and finance move on the *Bus health* row of the dashboard. The *placed-order's journey* row stitches these together for the demo narrative.

> Tip: with `NORTHWOOD_TRACING_SAMPLING=1.0` (the local default) every order is captured, so you can replay this immediately after placing one. Drop to `0.1` only when demonstrating prod-style sampling.

---

## AWS

The same stack runs on a single EC2 "observability box," gated on the Terraform variable `enable_observability` (`terraform/modules/infra-ec2/`). It pulls the identical `db/{tempo,loki,prometheus,grafana}` configs from an S3 artifacts bucket and runs the same container images (pinned in `variables.tf` → `observability_images`). The box's private DNS is exported as `observability_private_dns` (`outputs.tf`); feed it into the services' `OTLP_ENDPOINT` (`:4317`) and `LOKI_URL` (`:3100`). Prometheus on AWS currently self-scrapes only, with a commented service-discovery stub for future ECS SD. See commit `589a0c8` (§1D observability tier on AWS).
