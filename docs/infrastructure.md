# Northwood: the infrastructure

> **In short** — Northwood is deployed in two ways: on a developer machine, where Docker Compose runs
> the backing services and each Spring service runs on the host; and on AWS, where Terraform builds a
> three-box EC2 demo (web, app, data) behind a Caddy TLS edge. Both use one PostgreSQL database with a
> schema per service, a single Kafka broker, and Keycloak. The demo is built for showing and
> recovering, not for high availability.

**It builds on [`domain.md`](domain.md) and [`architecture.md`](architecture.md)**, cited as
*domain n* and *architecture n*.

---

## 1. The deployment topology

**Where does each part run? Locally, the backing services run in Docker and the Spring services run on
the host. On AWS, a public web box runs the edge, the BFF and Keycloak; a private app box runs the
seven services; a private data box runs PostgreSQL, Kafka and the telemetry stack.**

### 1.1 Local

| Runs in Docker Compose | Port | Runs on the host | Port |
|---|---|---|---|
| PostgreSQL 17 | 5432 | product · sales · inventory · manufacturing · purchasing · finance · reporting | 8081–8087 |
| Kafka 4.1.2 (KRaft, one node) | 9092 | erp-graphql-bff | 8088 |
| Keycloak 26.0 (`start-dev`) | 8090 | erp-web-ui-bff | 8089 |
| Redis 7 | 6379 | search-service | 8093 |
| Elasticsearch 9.0.4 (one node) | 9200 | erp-web-ui (Vite dev server) | 5174 |
| Kibana 9.0.4 | 5601 | | |
| Welcome page (nginx) | 8080 | | |
| Prometheus · Tempo · Loki · Promtail · Grafana | 9090 · 3200/4317/4318 · 3100 · — · 3000 | | |

The services reach the containers through `localhost`; Prometheus reaches the services through
`host.docker.internal`. Each container keeps its data in a named volume (`northwood-pgdata`,
`northwood-kafkadata`, …).

### 1.2 AWS demo

```
                Internet
                   │  :443 / :80
   ┌───────────────▼──────────────────────────── public subnet 10.0.1.0/24 ┐
   │  web EC2 10.0.1.10  (Elastic IP)                                      │
   │    Caddy :443 ─┬─ <ui host>      → nginx :8090 (SPA) → erp-web-ui-bff :8089
   │                ├─ <auth host>    → Keycloak :8080                     │
   │                └─ <grafana host> → Grafana on the data box (read-only)│
   │  NAT instance (t3.nano)                                               │
   └───────────────┬───────────────────────────────────────────────────────┘
   ┌───────────────▼──────────── private app subnet 10.0.2.0/24 ┐
   │  app EC2 10.0.2.10 : the 7 services, :8081–8087            │
   └───────────────┬────────────────────────────────────────────┘
   ┌───────────────▼──────────── private data subnet 10.0.3.0/24 ┐
   │  data EC2 10.0.3.10 : PostgreSQL, Kafka, Tempo, Loki,        │
   │                       Prometheus, Grafana                    │
   └──────────────────────────────────────────────────────────────┘
   Outside the VPC: CloudFront + S3 (welcome page), ECR (app images),
   Secrets Manager, S3 (artifacts, Terraform state), Route 53, EventBridge Scheduler.
```

| Box | Type | Runs |
|---|---|---|
| web | t3.small | Caddy, the SPA's nginx, `erp-web-ui-bff`, Keycloak |
| app | t3.medium | the seven services, each in its own container |
| data | t3.medium | PostgreSQL, Kafka, and (when `enable_observability`) Tempo, Loki, Prometheus, Grafana |
| NAT | t3.nano | iptables masquerade for the private subnets |

Each box runs `docker run --restart unless-stopped` containers set up by its user-data on first boot.
The boxes have fixed private IPs, so every cross-box URL is known at plan time. Only the web box is
public: `:443` for HTTPS and `:80` for the ACME challenge and the redirect. The app box accepts the
service ports from the web box (and from the data box for scraping); the data box accepts `:5432` and
`:9092` from the app box and the telemetry ports from both.

The welcome page is a static page in S3 behind CloudFront, so it stays up while the boxes are stopped.

---

## 2. The runtime environment

**What must the operator provide before the system works? A JDK, Maven, Docker and Node to build and
run; the backing services at the versions below; and, for AWS, an account, a Route 53 zone and
Terraform.**

| Need | Version | Why that version |
|---|---|---|
| Java | 21 | The language level of every module. |
| Maven | 3.9+ | The reactor build. |
| Node.js / npm | current LTS | Builds `erp-web-ui`. |
| Docker | with the `overlay2` image store | The local stack and the image build. A containerd image store pushes layers that the AWS hosts' Docker cannot pull (section 8). |
| PostgreSQL | 17 | The baseline schema and the test containers use 17. |
| Kafka | 4.1.2, KRaft | Matches the `kafka-clients` that Spring Boot 4.0.5 brings. |
| Keycloak | 26.0 | Imports `config/keycloak/northwood-realm.json`. |
| Redis | 7 | Sessions and the reporting cache. |
| Elasticsearch | 9.0.x | Spring Data Elasticsearch 6.0.4 brings client 9.2.6; keep the server on the same major. |
| Grafana · Prometheus · Tempo · Loki · Promtail | 11.3.1 · 3.0.1 · 2.7.0 · 3.3.0 · 3.3.0 | Tempo, Loki and Promtail move together. |

**For AWS, also:** Terraform 1.10 or later; AWS CLI v2, signed in; an IAM principal allowed to manage
VPC, EC2, ECR, Secrets Manager, IAM, S3, SSM, ACM, CloudFront, Route 53 and EventBridge Scheduler; a
Route 53 public hosted zone (`dns_zone_name`) whose sub-domains become the UI, auth, Grafana and
welcome-page names.

**The services start in any order** once PostgreSQL, Kafka and Keycloak are up: nothing calls another
service at start-up, and events wait in the outbox until their consumers run.

---

## 3. Configuration

**What can the operator set, and what is the default? Environment variables for every address, secret
and port; `northwood.*` properties for behaviour.** Every default suits the local stack. How the code
reads them is *architecture 9*.

### 3.1 Environment variables

| Variable | Default | Sets |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` | `kafka` turns on publishing, consuming and the dead-letter redriver. **Every cross-service flow needs it.** |
| `<SERVICE>_DB_URL` | `jdbc:postgresql://localhost:5432/northwood_erp?currentSchema=<svc>,shared` | The service's database. The one setting that changes when a schema moves to its own database. |
| `<SERVICE>_DB_USER`, `<SERVICE>_DB_PASSWORD` | `postgres` / `postgres` | The login. On AWS: `<svc>_service` and its generated password. |
| `<SERVICE>_PORT`, `SEARCH_PORT` | 8081–8087, 8093 | Listen port. The BFFs listen on 8089 and 8088. |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | The broker. |
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8090/realms/northwood` | The token issuer for every service and both BFFs. |
| `KEYCLOAK_BFF_CLIENT_SECRET` | `northwood-bff-secret` | The BFFs' client secret. Must equal the secret in the realm file. |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | Sessions (BFFs) and cache (reporting). |
| `ELASTICSEARCH_URIS` | `http://localhost:9200` | `search-service`'s cluster. |
| `NORTHWOOD_BFF_TARGETS_<SERVICE>` | `http://localhost:<port>` | Where the BFF sends each route. |
| `OTLP_ENDPOINT` | `http://localhost:4317` | Trace export (OTLP gRPC). |
| `LOKI_URL` | `http://localhost:3100/loki/api/v1/push` | Log push. |
| `NORTHWOOD_TRACING_SAMPLING` | `1.0` | Share of traces kept. `0.1` for a production-like run. |
| `ERP_URL`, `GRAFANA_URL` | `http://localhost:8089`, `http://localhost:3000` | Links on the welcome page. |

### 3.2 Behaviour properties

| Property | Default | Sets |
|---|---|---|
| `northwood.service-name` | per service | The topic `<name>.events` and the event source. |
| `northwood.outbox.drain.enabled` | `true` in `application-kafka.yml` of producers | Turns on the outbox drain and the topic declarations. |
| `northwood.outbox.poll-interval` | `1000` ms | Drain cadence. |
| `northwood.kafka.subscribe-topics` | per service | Topics the inbox listener reads. |
| `northwood.kafka.topic.partitions` | `3` | Partitions of the service's topics. Raising it is safe; lowering it is ignored. |
| `northwood.kafka.topic.replication-factor` | `1` | Replication of the service's topics. Raise it on a cluster of more than one broker. |
| `northwood.kafka.listener.concurrency` | the partition count | Listener threads, capped at the partition count. |
| `northwood.kafka.consumer.retry.initial-interval` / `multiplier` / `max-interval` / `max-elapsed-time` | `1000` ms / `2.0` / `30000` ms / `300000` ms | Back-off for transient consumer failures before dead-lettering. |
| `northwood.kafka.dlt.redrive.enabled` | `true` in consumers | Turns on the redriver. |
| `northwood.kafka.dlt.redrive.max-attempts` / `delay` / `concurrency` / `topic-pattern` | `5` / `10000` ms / `3` / `.+\.dlt` | Redrive limits. |
| `northwood.inbox.dedup-strategy` | `advisory-lock` | The inbox check (*architecture 5.4*). |
| `northwood.saga.poll-interval` / `lease-ttl-seconds` / `retry-backoff-seconds` | `1000` ms / `30` / `15` | Saga worker cadence, lease and retry. |
| `northwood.purchasing.shortagePoAutoApprove` | `true` | Sends a replenishment purchase order with a known price at once (*domain 5.5*, rule 3). |
| `northwood.finance.match.priceTolerancePercent` | `2.0` | The price tolerance of the three-way match (*domain 10*). |
| `northwood.reporting.dashboard.currencies` / `balanceRefreshIntervalMs` | `AUD` / `60000` | The financial dashboard. |
| `northwood.audit.enabled` | `true` (`false` in reporting and search) | The `/api/audit` endpoint. |
| `northwood.liquibase.enabled` | `false` | Liquibase per service (section 6.3). |
| `northwood.bff.web-ui-url` | `http://localhost:5174/` | Where the BFF sends the browser after sign-in. |

### 3.3 Terraform variables (AWS)

`region`, `name_prefix` (`northwood-demo`), `image_tag`, `dns_zone_name`, `ui_hostname`,
`auth_hostname`, `grafana_hostname` (empty keeps Grafana private), `front_door_domain`, `acme_email`,
`enable_observability` (`true`), `load_seed_data`, `enable_scheduler` (`true`), `start_cron`
(`cron(30 8 ? * MON-FRI *)`), `stop_cron` (`cron(0 17 ? * MON-FRI *)`), `scheduler_timezone`
(`Australia/Sydney`), `bff_client_secret` (must equal the realm file), `repo_root`. The modules also
take `ami_id` / `nat_ami_id`; empty takes the latest Amazon Linux 2023 when a box is first created.

---

## 4. Security operations

**How is the deployment kept safe, and who changes what? HTTPS at one edge, each service on its own
database role, secrets generated and kept in Secrets Manager, and administration only over SSM. The
local stack has no security of its own and must never be exposed.**

### 4.1 TLS and certificates

| Name | Certificate | Renewed by |
|---|---|---|
| UI, auth, Grafana hosts | Let's Encrypt, issued by Caddy on the web box (HTTP-01 on `:80`) | Caddy, automatically |
| Welcome page | ACM, validated through Route 53, on CloudFront | ACM, automatically |

Behind Caddy everything is plain HTTP on loopback or inside the VPC. The BFF runs with
`server.forward-headers-strategy=framework` and Keycloak with `KC_PROXY_HEADERS=xforwarded`, so both
build `https://` URLs from Caddy's forwarded headers. At import, the web box rewrites the realm's
`localhost` redirect URIs and web origins to the UI host.

### 4.2 Accounts, keys and secrets

| Secret | Made by | Kept in | Rotated by |
|---|---|---|---|
| One password per `<svc>_service` database role | Terraform (`random_password`) | Secrets Manager `…/db/<svc>`; the services' env file in the private artifacts bucket | Re-apply with a new value, then replace the data and app boxes. |
| PostgreSQL superuser password | Terraform | Secrets Manager `…/db/postgres-superuser` | As above. |
| Keycloak admin password | Terraform | Secrets Manager `…/keycloak/admin` | As above, then replace the web box. |
| BFF client secret | Fixed: `northwood-bff-secret` | Secrets Manager `…/keycloak/bff-client-secret` and the realm file | Change the realm file **and** the variable together. |

- The baseline schema creates the service roles `NOLOGIN`. On AWS, an extra init script turns on
  `LOGIN` with each generated password, so each service really connects as its own role. Locally,
  every service connects as `postgres`.
- **Terraform state holds these secrets in plain text**, so the state lives in a private, encrypted,
  versioned S3 bucket made once by `terraform/bootstrap` (protected against destroy).
- No box has an SSH key. Shells and the Grafana admin port are reached through SSM Session Manager.
  The instance role may pull from the project's ECR repositories and read its artifacts bucket.

### 4.3 Exposure

- **AWS:** only Caddy is public. Grafana is public read-only (anonymous Viewer) through Caddy; admin is
  an SSM port-forward to `:3000`. Kafka and PostgreSQL accept only the app box.
- **Local:** Keycloak in dev mode over HTTP, Grafana with anonymous Admin, Elasticsearch with security
  off, PostgreSQL `postgres/postgres`, Kafka with no authentication. Every port is bound on the host.
  This is for one developer's machine only.
- The realm has one demo user per role (*domain 3.8*), which is for showing, not for real use.

---

## 5. Non-functional requirements

**What qualities must the system have, and how does it meet them? Correct under concurrency and
redelivery, recoverable from any failure but the loss of PostgreSQL, observable end to end, and ready to
split. It is not highly available.**

| Quality | Requirement | How it is met |
|---|---|---|
| **No lost or doubled effect** | Every committed change is published at least once and applied exactly once by each handler. | Transactional outbox, publish-then-mark, inbox per handler (*architecture 5*). Tested against real Kafka and PostgreSQL. |
| **Correct under concurrency** | Concurrent users and redelivered events never break an invariant of *domain 5*. | Optimistic locking, `SKIP LOCKED` claims, guarded atomic writes, database constraints. The Gatling load test runs 30 concurrent users through order-to-cash and checks the invariants afterwards. |
| **Recovery** | Every failure except the loss of PostgreSQL without a backup recovers without losing data. | Section 7.3. |
| **Scale out** | A service can run on more than one instance. | No state outside PostgreSQL; outbox and Saga claims use `SKIP LOCKED` and leases; sessions in Redis; listener threads up to the partition count. |
| **Ready to split** | Moving a service to its own database changes one setting. | Schema per service, own role, own pool, no cross-schema key or query (*architecture 12.1*). |
| **Observable** | One business transaction can be followed across services in traces, logs and metrics. | Section 7.1. |
| **Availability** | None beyond restart. | One instance of everything. PostgreSQL and the Kafka broker are single points of failure. |
| **Cost (AWS demo)** | Low enough to keep running. | Small boxes, a NAT instance, weekday schedule; stopped, it costs storage and the Elastic IP only. |

---

## 6. Build and release

**How does a change reach a deployment? Maven builds and tests the reactor; Spring Boot buildpacks
make one image per app; locally you run the jars, on AWS you push to ECR and replace the boxes. The
schema is one baseline script.**

### 6.1 Build and test

```powershell
mvn clean install -DskipTests     # build every module
mvn test                          # domain, application, acceptance and shared tests; no Docker
mvn verify                        # also the *IT tests on Testcontainers PostgreSQL 17 and Kafka; needs Docker
cd erp-web-ui; npm ci; npm run build   # the SPA (npm run build is its type check)
```

- After changing any port interface, build the whole reactor: `test-harness` holds an in-memory double
  of every port, and a single-module build does not see it break.
- JaCoCo writes a report per module and an aggregate in `test-harness`. There is no coverage gate.
- The load test (`load-test`) runs against a running stack with Gatling.

### 6.2 Images and release (AWS)

1. Once: `terraform/bootstrap` (state bucket), then `terraform apply -target=module.ecr` in
   `terraform/envs/demo`.
2. Every release: `terraform/build/build-and-push.ps1 -Tag <tag>` builds each image with
   `spring-boot:build-image`, pushes it to ECR, and builds the SPA into `erp-web-ui/dist`.
3. `terraform apply`, adding `-replace=module.compute.aws_instance.app` (and `.web`) to make the boxes
   pull the new images. *Reason:* user-data runs only when a box is created, so a plain apply changes
   nothing on a running box.

The images are the seven services and `erp-web-ui-bff`. The SPA is staged to S3 and served by the web
box's nginx.

### 6.3 Schema lifecycle

- `config/postgresql/northwood_erp.sql` holds the schema, roles and grants. PostgreSQL runs it once,
  on the first boot of an empty volume. `config/postgresql/northwood_erp_seed.sql` (overlay
  `docker-compose.seed.yml`, or `load_seed_data` on AWS) adds the demo data; it can be run again
  safely.
- Liquibase is off. **A schema change is made in the baseline script.** A running database that must
  keep its data (the AWS demo) needs the same change applied by hand with `ALTER`, because the
  baseline does not run again.
- A Saga state added or removed in code must change in its table's `saga_state` CHECK in the same
  release, or the service will not start (*architecture 6.4*).
- To start clean locally: `docker compose down -v`, then `up -d`.

---

## 7. Operations

**How is the running system watched and kept working? Through Grafana over traces, logs and metrics,
a small set of known recovery steps, and starting and stopping the AWS boxes. There are no backups.**

### 7.1 Telemetry

| Signal | Path | Look at |
|---|---|---|
| Traces | Services push OTLP gRPC to Tempo (`OTLP_ENDPOINT`). | Grafana → Tempo. One Saga: `{ .northwood.saga_id = "<id>" }`; one order and its sub-Sagas: `{ .northwood.sales_order_id = "<id>" }`. |
| Logs | Services push to Loki (`LOKI_URL`); Promtail ships the containers' logs. | Grafana → Loki, by `app` and by trace id. |
| Metrics | Prometheus scrapes `/actuator/prometheus` every 15 s (services, web BFF). Kept 24 h. | `northwood-overview` dashboard; `northwood_outbox_pending`, `northwood_saga_count`, HTTP RED metrics. |

### 7.2 Routine

- **Local start:** `docker compose up -d` (add the seed overlay for demo data), then each service with
  `SPRING_PROFILES_ACTIVE=kafka`.
- **AWS pause:** `terraform/ops/stop.ps1` / `start.ps1 -Wait`, or the weekday schedule (08:30–17:00
  Sydney, stopped all weekend). Stopping keeps the disks, the IPs and the images. Terraform sees no
  drift.
- **AWS teardown:** `terraform destroy` (the state bucket stays).

### 7.3 Recovery

| Failure | Class | Step |
|---|---|---|
| A service, container or box restarts | automatic | None: in-flight events are delivered again, Saga leases expire and are claimed. |
| Kafka or PostgreSQL down, then back (data kept) | automatic | None: the outbox retries the broker for ever; consumers back off for up to 5 min. |
| A record dead-lettered by a passing fault | automatic | None: the redriver applies it again. |
| A record in `<topic>.dlt.parked` | manual | Find it (the redriver logs `ERROR … parked in …`), fix the cause, publish the record again onto its source topic. There is no tool for this. |
| An outbox row that keeps failing | manual | `status = 'failed'`, read `last_error`; fix the cause, or correct or remove the row. |
| A Saga that keeps failing | manual | Rising `retry_count` and `last_error` on the Saga row; fix the cause, or change the row by SQL. |
| A malformed envelope (logged and skipped) | manual | Publish the event again from the producer's outbox. |
| Kafka's volume lost | manual | Set the affected outbox rows from `published` to `pending`; the inbox absorbs the duplicates. |
| A read model lost or wrong | manual | Elasticsearch: drop the index and replay. Reporting: clear the view and its `inbox_message` rows, then replay from the earliest offset. |
| **PostgreSQL's volume lost** | **unrecoverable** | No backup exists. Start clean from the baseline and the seed. |

---

## 8. Why the less obvious operational choices are there

| Choice | Reason |
|---|---|
| EC2 with `docker run`, not ECS or Fargate | The smallest thing that shows the whole system; the managed design is a separate, larger variant. |
| A NAT instance, not a NAT Gateway | A gateway costs about USD 32 a month. The free S3 gateway endpoint still keeps image-layer traffic off the NAT. The NAT instance installs iptables before it uses it, because Amazon Linux 2023 has none. |
| Keycloak public, behind Caddy | The browser must reach the issuer for the sign-in redirect; the services reach the same public issuer through the NAT, so no split issuer is needed. |
| Fixed private IPs | Cross-box URLs are known at plan time, so there is no dependency cycle between boxes. |
| `ignore_changes = [ami]` | A new AMI would replace the box, which on the data box destroys PostgreSQL and Kafka. Moving to a new AMI is a deliberate `-replace`. |
| Fixed Kafka `CLUSTER_ID` | A re-created container can attach to the kept volume. |
| Broker auto-create off (locally) | A misspelt topic fails loudly instead of becoming a stray one-partition topic. |
| Single broker, replication 1 | Recovery after a restart is enough for a demo; the outbox makes even a lost broker recoverable. |
| Welcome page on CloudFront and S3 | It explains the demo hours while the boxes are stopped. |
| `overlay2`, not the containerd image store | Images pushed from a containerd store carry uncompressed layers that Docker 25 on Amazon Linux 2023 refuses ("layers from manifest don't match image configuration"), so no container starts. |
| ECR login through a separate Docker config | `docker login` to ECR fails on some Docker Desktop versions; the build script writes a separate config and passes it to `docker push --config`. |
| 100 % trace sampling | A demo is for looking at traces; production would use about 10 %. |
| Scheduled and security spans off | They would fill Tempo with empty spans every second. |

---

## 9. Left out, and open

**Left out:**
- High availability: Multi-AZ PostgreSQL (or Aurora), a Kafka cluster, more than one instance per
  service.
- Backups and point-in-time recovery for PostgreSQL. Adding them would make the one unrecoverable row
  of section 7.3 recoverable.
- Alerting on parked records, failing outbox rows, stuck Sagas or error rates.
- The managed AWS design (ECS Fargate, Aurora, MSK, Cognito, an ALB).

**Open:**
1. **The AWS demo runs no Redis, Elasticsearch, `search-service` or `erp-graphql-bff`.** The web BFF keeps
   its sessions in Redis and routes `/api/search` to `search-service`, so sign-in and search on AWS
   depend on parts the topology does not have. Add them to the data and app boxes, or change the BFF's
   session store for AWS?
2. **The AWS broker creates topics by itself** (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`), unlike the local
   one. Turn it off on AWS too?
3. **Prometheus on AWS does not scrape the services** (it has no service discovery), so service metrics
   are empty there. Add static targets, or push metrics over OTLP?
4. **Local Prometheus does not scrape `erp-graphql-bff` (8088) or `search-service` (8093).** Add them?
5. **There is no tool to replay parked dead-letter records.**
