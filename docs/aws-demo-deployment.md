# Northwood ERP — AWS demo deployment

A **demo-grade** deployment of the Northwood stack on AWS, with the three stateful
infra components — **PostgreSQL, Kafka, Keycloak** — each containerized on its own
EC2 instance, all sharing one VPC subnet. This is *not* production-grade (single Kafka
broker, no Multi-AZ, Keycloak in dev mode); see [Path to production](#11-path-to-production)
for the hardening route.

It reuses the container images and configuration already in `docker-compose.yml`
and `db/` — the only behavioural change from local is that each component now lives
on a separate host, so a few "advertised address" settings must point at private
DNS instead of `localhost`.

---

## 1. Scope & assumptions

**Specified (Part A — the core):** Postgres, Kafka, Keycloak, each on its own
dedicated EC2 instance, all sharing a single subnet.

**Added to make it a runnable demo (Part B — adjust if hosting elsewhere):** a **web
tier** (the 2 BFFs) + an **app tier** (the 7 services), an optional **observability**
EC2 (the LGTM stack, §5.4) in the infra subnet, the 2 SPAs on S3 + CloudFront, and an
ALB for ingress.
If you intend to run the services from your laptop against the AWS infra instead,
skip Part B and instead expose the three infra ports to your IP via the security
groups + a VPN/bastion (not recommended over the public internet).

Assumptions: a single region, **single AZ** (cheapest demo); the three infra hosts
share **one private subnet**, each isolated by its own security group; Amazon Linux
2023 AMIs, access via **SSM
Session Manager** (no SSH keys, no public bastion). Demo-grade secrets carried over
from the repo — **override before any non-localhost exposure** (see §10).

---

## 2. Architecture

```
                          Internet
                             │ 443
                      ┌──────▼──────┐
 public 10.0.1.0/24   │     ALB     │  (+ NAT GW for image pulls)
                      └──┬───────┬──┘
        BFFs 8080/8089 ──┘       └── 8080 Keycloak (OIDC browser flow)
   ┌───────────────────────────────┐
   │  web subnet  10.0.2.0/24       │  BFFs — internet-facing tier
   │  demo-bff(8080) erp-bff(8089)  │
   └───────────────┬───────────────┘
                   │  REST 8081-8087   (+ erp-bff → Keycloak :8080)
   ┌───────────────▼───────────────┐
   │  app subnet  10.0.3.0/24       │  7 services — internal only
   │  services (8081-8087)          │
   └───────────────┬───────────────┘
                   │  5432 · 9092   ·   traces/logs → tempo/loki
   ┌───────────────▼─────────────────────────────────────────┐
   │  infra subnet  10.0.11.0/24   — separate EC2s:            │
   │    • postgres:17 (:5432)   • kafka:4.1.2 (:9092)          │
   │    • keycloak:26 (:8080)                                  │
   │    • observability — 1 EC2, LGTM (§5.4):                  │
   │        grafana:3000 prometheus:9090 tempo:4317 loki:3100  │
   └───────────────────────────────────────────────────────────┘
        ▲ Prometheus scrapes /actuator/prometheus on the web + app tiers

SPAs (erp-web-ui, demo-web-ui) → static build → S3 + CloudFront (outside the VPC)
```

| Component | Subnet | Runs on | Reached by |
|---|---|---|---|
| 2 BFFs (`demo-bff`, `erp-bff`) | `10.0.2.0/24` (web) | EC2, containers | ALB |
| 7 services | `10.0.3.0/24` (app) | EC2(s), containers | web tier only |
| PostgreSQL 17 | `10.0.11.0/24` (infra) | own EC2, container | app tier :5432 |
| Kafka 4.1.2 (KRaft) | `10.0.11.0/24` (infra) | own EC2, container | app tier :9092 |
| Keycloak 26 | `10.0.11.0/24` (infra) | own EC2, container | app + web tier + ALB :8080 |
| Observability (LGTM) | `10.0.11.0/24` (infra) | 1 EC2, 5 containers | scrapes web+app; ALB → Grafana |
| 2 SPAs (`erp-web-ui`, `demo-web-ui`) | — (outside VPC) | S3 + CloudFront | Internet |
| ALB + NAT GW | `10.0.1.0/24` (public) | managed | Internet / outbound |

---

## 3. Prerequisites

- AWS account; an IAM principal with VPC/EC2/ALB/S3/CloudFront/IAM permissions.
- The repo checked out (for `db/northwood_erp.sql`, `db/northwood_erp_seed.sql`,
  `db/keycloak/northwood-realm.json`, and to build the apps/SPAs).
- An S3 bucket to stage the `db/` files and built app artifacts (the private
  instances pull from here via NAT or an S3 VPC endpoint).
- (Optional) a domain + ACM certificate for TLS on the ALB. Without one, use the
  ALB DNS name over HTTP for the demo.

---

## 4. Network layer

**VPC** `10.0.0.0/16`. One AZ. Subnets:

| Subnet | CIDR | Type | Purpose |
|---|---|---|---|
| public | `10.0.1.0/24` | public | ALB, NAT GW |
| web | `10.0.2.0/24` | private | 2 BFFs (`demo-bff`, `erp-bff`) — internet-facing tier |
| app | `10.0.3.0/24` | private | 7 services (8081-8087) — internal only |
| infra | `10.0.11.0/24` | private | Postgres, Kafka, Keycloak + observability — 4 separate EC2s |

- **IGW** on the VPC; public subnet routes `0.0.0.0/0` → IGW.
- **NAT Gateway** in the public subnet; private subnets route `0.0.0.0/0` → NAT
  (so instances can pull container images). Alternatively add S3/ECR/SSM **VPC
  endpoints** to avoid NAT cost.
- **Access:** attach an instance profile with `AmazonSSMManagedInstanceCore` to every
  EC2 and use **SSM Session Manager** for shell access — no SSH, no key pairs, no
  public IPs on the infra boxes.

**Security groups** (least privilege):

| SG | Inbound | From |
|---|---|---|
| `alb-sg` | 80, 443 | `0.0.0.0/0` |
| `web-sg` (BFFs) | 8080, 8089 | `alb-sg`, `obs-sg` (scrape) |
| `app-sg` (services) | 8081-8087 | `web-sg`, `obs-sg` (scrape) |
| `postgres-sg` | 5432 | `app-sg` |
| `kafka-sg` | 9092 | `app-sg` |
| `keycloak-sg` | 8080 | `app-sg`, `web-sg`, `alb-sg` |
| `obs-sg` (Grafana) | 3000 | `alb-sg` |
| `obs-sg` (Tempo/Loki ingest) | 4317, 4318, 3100 | `web-sg`, `app-sg` |

(Only the two BFFs (`web-sg`) are reachable from the ALB; the 7 services (`app-sg`) are
reachable only from the web tier, never the internet. The BFFs reach the services and
(erp-bff) Keycloak — **not** Postgres or Kafka, so `web-sg` has no rule to the DB/bus.
Observability flips direction: **Prometheus scrapes** the web+app tiers (so they allow
inbound from `obs-sg`), while the services **push** traces/logs *to* Tempo/Loki (`obs-sg`
inbound from web+app). The four infra instances share one subnet but each keeps its own
SG, so the per-port rules above isolate them at the instance level.)

---

## 5. Infra tier (Part A — your spec)

Each box: Amazon Linux 2023, `t3.small`–`t3.medium`, a gp3 EBS data volume, Docker
installed via user-data, the container started with `--restart unless-stopped`.

Common user-data preamble:
```bash
#!/bin/bash
dnf install -y docker
systemctl enable --now docker
```

### 5.1 PostgreSQL EC2 (`10.0.11.0/24`)

Stage `db/northwood_erp.sql` (+ `db/northwood_erp_seed.sql` for a populated demo) to
the instance (e.g. `aws s3 cp` from your staging bucket), then:

```bash
docker run -d --name northwood-postgres --restart unless-stopped \
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD='<STRONG_PW>' \
  -e POSTGRES_DB=northwood_erp \
  -p 5432:5432 \
  -v /data/pg:/var/lib/postgresql/data \
  -v /opt/northwood/northwood_erp.sql:/docker-entrypoint-initdb.d/01-northwood_erp.sql:ro \
  -v /opt/northwood/northwood_erp_seed.sql:/docker-entrypoint-initdb.d/02-northwood_erp_seed.sql:ro \
  postgres:17
```
The init scripts run **once on a fresh volume**: `01` creates the per-service schemas,
the `<service>_service` roles and grants; `02` loads the demo fixtures (drop it for an
empty schema). This is one database serving all services via schema-per-service — no
change to the app's connection model.

### 5.2 Kafka EC2 (`10.0.11.0/24`)

> ⚠️ **The one mandatory change vs. the local compose.** Locally Kafka advertises
> `PLAINTEXT://localhost:9092`, which only works on the same host. Across instances
> you **must** advertise the Kafka box's **private DNS/IP** so app-tier clients can
> connect — they're on a different host (regardless of subnet). Set `KAFKA_ADVERTISED_LISTENERS` accordingly.

```bash
KAFKA_DNS=$(hostname -f)   # private DNS of this instance
docker run -d --name northwood-kafka --restart unless-stopped \
  -p 9092:9092 \
  -v /data/kafka:/var/lib/kafka/data \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://$KAFKA_DNS:9092 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=true \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_SHARE_COORDINATOR_STATE_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_DEFAULT_REPLICATION_FACTOR=1 \
  -e KAFKA_MIN_INSYNC_REPLICAS=1 \
  -e CLUSTER_ID=4L6g3nShT-eMCtK--X86sw \
  apache/kafka:4.1.2
```
Single-broker KRaft with the RF=1 overrides + auto-create-topics carried over from the
compose — keeping it single-broker is *why* the demo path avoids the §2.14
pre-declared-topics work.

### 5.3 Keycloak EC2 (`10.0.11.0/24`)

Stage `db/keycloak/northwood-realm.json`, then:

```bash
docker run -d --name northwood-keycloak --restart unless-stopped \
  -p 8080:8080 \
  -v /data/keycloak:/opt/keycloak/data \
  -v /opt/northwood/northwood-realm.json:/opt/keycloak/data/import/northwood-realm.json:ro \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD='<STRONG_PW>' \
  -e KC_HTTP_PORT=8080 -e KC_HEALTH_ENABLED=true \
  -e KC_HOSTNAME_STRICT=false \
  -e KC_HOSTNAME=https://auth.<your-domain>   # the public URL clients use to reach Keycloak \
  quay.io/keycloak/keycloak:26.0 start-dev --import-realm
```
> The OIDC **issuer** is baked into tokens from `KC_HOSTNAME`. It must match the URL
> the browser/BFF actually uses to reach Keycloak (i.e. the ALB hostname), or token
> validation and redirects fail. If you're skipping OAuth2 for the demo, Keycloak can
> stay internal-only and this matters less, but set it correctly the moment the OIDC
> login is exercised.

### 5.4 Observability EC2 (`10.0.11.0/24`) — optional, the LGTM stack

From the `feat/1d-observability` branch. Co-located on a **single** EC2 in the infra
subnet — Grafana + Prometheus + Tempo + Loki + Promtail are built to run together, so one
box (not five) is the right call for a demo. Stage the provisioning configs (`db/prometheus/`,
`db/tempo/`, `db/loki/`, `db/promtail/`, `db/grafana/`) and start the compose's
*Observability* block:

```bash
docker compose -f docker-compose.yml up -d prometheus tempo loki promtail grafana
```

Two differences from local:

- **Prometheus targets** — repoint `db/prometheus/prometheus.yml` at the web/app **private
  DNS** (`<svc-dns>:8081-8087`, `<bff-dns>:8080/8089`, path `/actuator/prometheus`), not
  `host.docker.internal`.
- **Promtail** tails Docker logs on its **own host only**, so it can't see the Postgres /
  Kafka / Keycloak logs on the other EC2s. App/BFF logs still reach Loki via the
  `loki-logback-appender` (app-side push); for the infra-container logs, run a Promtail
  agent on each infra box or skip them.

Services point their OTLP exporter at `<obs-dns>:4317` (Tempo) and the Loki appender at
`http://<obs-dns>:3100/loki/api/v1/push`. **Grafana** (`:3000`) is an ALB target (or reach
it via SSM port-forward); it runs **anonymous-admin** in demo mode — don't expose it
without auth.

---

## 6. Web & app tiers (Part B — to complete the demo)

Build the 9 Spring Boot apps into images (`mvn spring-boot:build-image`, no Dockerfiles
needed) and run the **2 BFFs in the web subnet** (`10.0.2.0/24`) and the **7 services in
the app subnet** (`10.0.3.0/24`) — each tier can be one EC2 with a `docker compose`, or
several. Point each at the infra boxes by **private DNS**. The DB / Kafka / kafka-profile
env below is for the **services**; the BFFs need only the downstream service URLs and
(erp-bff) the Keycloak issuer + client secret:

| Env | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `kafka` — **mandatory**; without it the outbox publisher + consumers don't register and cross-service flows silently never fire |
| `<SERVICE>_DB_URL` | `jdbc:postgresql://<postgres-private-dns>:5432/northwood_erp` (per-service role + `search_path` set as today) |
| `<SERVICE>_DB_PASSWORD` | from Secrets Manager, **not** the committed default |
| Kafka bootstrap | `<kafka-private-dns>:9092` |
| OTLP traces / Loki logs | `<obs-private-dns>:4317` (Tempo) · `http://<obs-private-dns>:3100/loki/api/v1/push` |
| Keycloak issuer | `https://auth.<your-domain>/realms/northwood` (via ALB) |
| `KEYCLOAK_BFF_CLIENT_SECRET`, `NORTHWOOD_SECURITY_DEMOBYPASS_TOKEN` | from Secrets Manager |

Only the two BFFs (`web-sg`, 8080 / 8089) are ALB targets; the 7 services (`app-sg`) are
reachable only from the web tier, never the internet.

### 6.1 SPAs — `erp-web-ui` and `demo-web-ui`

Both SPAs are static Vite/React builds, so they **don't run on a subnet at all**: build
each (`npm run build`) and host the static output on **S3 + CloudFront** (cheap, scalable,
no EC2 — their only public surface is the CDN). Point each SPA's API base at its BFF via
the ALB. (A quick-and-dirty alternative is serving the bundle from an nginx container in
the web tier, but S3 + CloudFront is the recommended path.)

---

## 7. Ingress & TLS

- **ALB** in the public subnet, listeners 80→redirect→443. Target groups:
  `demo-bff` (8080), `erp-bff` (8089), `keycloak` (8080, browser OIDC flow), and
  `grafana` (3000, the observability UI).
  Health checks hit Actuator `/actuator/health` on the BFFs.
- **TLS:** an ACM certificate on the 443 listener (needs a domain in Route 53 or your
  registrar). No domain? Run the demo on the ALB DNS over HTTP (fine for a throwaway demo).

---

## 8. Bring-up order

Dependencies matter — the services fail fast if Kafka/DB aren't ready:

1. **Network**: VPC, subnets, IGW, NAT, route tables, security groups.
2. **Postgres EC2** → wait for `pg_isready` (the `01`/`02` init scripts run on first boot).
3. **Kafka EC2** → wait ~30 s for the broker to be reachable on `<kafka-dns>:9092`.
4. **Keycloak EC2** → realm imported, admin console reachable.
5. **Observability EC2** (optional): start the LGTM stack — it tolerates the app/web tiers
   not being up yet (Prometheus just shows those targets down until they start).
6. **App tier** (services): bring up the 7 services with `SPRING_PROFILES_ACTIVE=kafka`
   and the infra private-DNS env above; each applies its (empty) Liquibase master
   changelog against the loaded baseline. Then the **web tier**: the 2 BFFs.
7. **SPAs** (`erp-web-ui`, `demo-web-ui`) → S3 / CloudFront, then the **ALB** target registrations.

> Start Kafka *before* the services — the first publish fails if the broker isn't up yet.

---

## 9. Verification (smoke)

- `psql -h <postgres-dns> -U sales_service -d northwood_erp -c "select count(*) from sales.customer;"` → seeded rows (e.g. `CUST-001`).
- On the Kafka box: `kafka-topics.sh --bootstrap-server localhost:9092 --list` after a flow runs → `*.events` topics.
- Keycloak admin console at the ALB `/admin` → `northwood` realm, 13 users.
- BFF health: ALB `/actuator/health` → `UP`.
- Drive a place-order in the SPA and watch the Saga Console advance across services.
- Grafana (ALB `/` or SSM-forward `:3000`) → Prometheus targets **UP**, the Northwood
  dashboard renders, and a place-order produces a trace in Tempo.

---

## 10. Security (demo-grade — read before exposing)

- **Override every committed demo secret** via Secrets Manager / SSM: the 7 service DB
  passwords, the Keycloak BFF client secret, and the demo-bypass token. Set
  `NORTHWOOD_SECURITY_DEMOBYPASS_TOKEN` empty to **disable** the synthetic-auth bypass
  if you're not demoing the demo-SPA path.
- Infra boxes have **no public IP**; access only via SSM. Only the ALB is internet-facing.
- Security groups are tier-to-tier (table in §4), not `0.0.0.0/0`.
- Keycloak runs `start-dev` (HTTP, no clustering) and Kafka is a single broker — both
  **demo-only**.
- Grafana runs **anonymous-admin** in demo mode — front it with auth (or keep it
  SSM-only) before any exposure.

---

## 11. Path to production

When this graduates past a demo (tracked in `docs/dev-todo.md`):

- **§3.7** — per-service independent scaling (ECS Fargate or per-service ASGs), HA
  Postgres via **RDS Multi-AZ / Aurora** + **RDS Proxy**, SPAs on S3+CloudFront.
- **§2.14** — pre-declared Kafka topics (RF≥3) once you move to a multi-broker bus
  (managed **MSK**), or **§3.6** — swap the bus for **SNS+SQS FIFO** (AWS-native).
- Keycloak in production mode (HTTPS, external DB, clustered) or migrate to Cognito.

---

## 12. Rough cost (always-on, single-AZ, on-demand)

| Item | ~Monthly |
|---|---|
| 3 infra EC2 (`t3.small`) + 1 obs EC2 (`t3.medium`, LGTM) + web/app EC2 (`t3.medium`) | ~$120–200 |
| NAT Gateway | ~$32 + data |
| ALB | ~$16 + LCUs |
| S3 + CloudFront (SPAs) | a few $ |
| EBS volumes | ~$8–15 |
| **Total** | **~$180–280** |

Stop the EC2 instances (and delete the NAT GW) when you're not demoing to cut this to
near zero — the EBS volumes preserve state across stops.

---

## 13. Teardown

Terminate the EC2 instances → delete the ALB + target groups → delete CloudFront +
empty/delete the S3 bucket → delete the NAT GW (release its EIP) → delete subnets,
route tables, IGW, security groups → delete the VPC. (Deleting the NAT GW promptly is
the biggest cost saver.)
