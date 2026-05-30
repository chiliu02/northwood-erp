# Northwood demo — Terraform (Part A + app tier)

Automates **docs/aws-demo-deployment.md** Part A (network + Postgres/Kafka/Keycloak
on EC2), the app tier (ECR + Secrets Manager + ECS Fargate ×9 + ALB), and the §1D
observability EC2 (LGTM stack). The 2 SPAs (S3/CloudFront) remain **out of scope** here.

```
terraform/
  bootstrap/                 # one-time: creates the S3 state bucket (local state)
  envs/demo/                 # the root config you apply
    backend.tf               # <- edit: set the state bucket name
    main.tf                  # module wiring + the services/bffs maps
    services.tf              # the 9 deployables (ecs-service for_each ×2)
    variables.tf / outputs.tf / terraform.tfvars.example
  modules/
    network/                 # VPC, 5 subnets (2 public for the ALB), NAT, SGs (§4)
    infra-ec2/               # postgres/kafka/keycloak + observability EC2 + user-data + artifacts bucket
    ecr/ secrets/            # 9 repos; Secrets Manager (DB pwds, BFF secret, bypass token)
    ecs-cluster/ ecs-service/# Fargate cluster + Service Connect; reusable per-app service
    alb/                     # public ALB + BFF target groups
  build/                     # build-and-push.{ps1,sh}: buildpack images -> ECR
```

## Prerequisites

- Terraform >= 1.10, AWS CLI v2 (authenticated), Docker daemon, Maven, Java 21.
- An IAM principal with VPC/EC2/ECS/ECR/ELB/Secrets-Manager/IAM/S3 permissions.
- (Optional) an ACM cert + domain for HTTPS; otherwise the demo runs HTTP on the ALB DNS.

## Bring-up

```powershell
# 1. State backend (local-state bootstrap; run once).
cd terraform/bootstrap
terraform init
terraform apply -var="state_bucket_name=northwood-tfstate-<your-account-id>"

# 2. Point the real config at that bucket.
#    Edit terraform/envs/demo/backend.tf -> bucket = "<the name above>"  (+ region if not Sydney)

# 3. Init the demo env.
cd ../envs/demo
terraform init
cp terraform.tfvars.example terraform.tfvars   # optional: adjust region/tag/scaling

# 4. Create ECR repos first, so there's somewhere to push images.
terraform apply -target=module.ecr

# 5. Build all 9 apps as images and push to ECR.
../../build/build-and-push.ps1 -Tag latest        #  bash: ../../build/build-and-push.sh latest

# 6. Apply everything else (network, infra EC2s, secrets, ECS, ALB).
terraform apply
```

> **Why the two-phase apply (4 → 5 → 6).** ECS tasks can't pull images that aren't in
> ECR yet. `aws_ecs_service` doesn't block on task health (`wait_for_steady_state`
> is off), so a single `apply` would *succeed* while every task crash-loops on
> `CannotPullContainerError`. Creating the repos, pushing, then applying the rest
> avoids that. If you ever change app code: rebuild/push (step 5), then
> `aws ecs update-service --cluster <name> --service <svc> --force-new-deployment`.

Bring-up dependency order within step 6 (Postgres → Kafka → Keycloak → services → BFFs)
is handled by Terraform's graph + the `depends_on` on the BFF services; the EC2
user-data pulls `db/` + the generated role-login script from the private artifacts
bucket on first boot.

## Verify (mirrors §9)

```powershell
terraform output alb_dns_name           # open http://<dns>/  (demo-bff)  ·  /erp (erp-bff)
terraform output infra_instance_ids     # aws ssm start-session --target <postgres-id>
# on the postgres box:  docker exec -it northwood-postgres psql -U postgres -d northwood_erp \
#                         -c "select count(*) from sales.customer;"
```

## Teardown

```powershell
cd terraform/envs/demo
terraform destroy
# The state bucket (bootstrap) has prevent_destroy = true — delete it by hand if you
# truly want it gone. Stopping the 3 EC2s + scaling ECS to 0 is the cheaper "pause".
```

---

## Design decisions worth knowing

- **Per-service DB login roles (the load-bearing invariant, made real).** The baseline
  `db/northwood_erp.sql` ships the `<svc>_service` roles as `NOLOGIN` (in dev every
  service logs in as `postgres`). Terraform generates a password per service, renders an
  `ALTER ROLE <svc>_service LOGIN PASSWORD …` init script (staged as `03-service-logins.sql`),
  and each ECS task connects as its own role — so the schema-per-service least-privilege
  isolation is genuinely enforced on AWS. **Fallback:** if the baseline grants turn out
  incomplete for login-as-role and a service can't read its own tables, set that service's
  `<SERVICE>_DB_USER` back to `postgres` in `services.tf` (and point its `_DB_PASSWORD`
  secret at `…/db/postgres-superuser`).

- **State holds secrets → S3 backend.** Terraform sets the Secrets Manager values, so
  state contains them in plaintext; that's why state lives in the private+encrypted+
  versioned bootstrap bucket, not a local file.

- **Keycloak BFF client secret is fixed, not random.** It must equal the `"secret"` baked
  into `db/keycloak/northwood-realm.json` (`northwood-bff-secret`). Rotating it means
  editing the realm JSON too.

- **ALB needs 2 AZs; the demo is otherwise single-AZ.** The network module adds a second
  *public* subnet in a second AZ purely to satisfy the ALB. No workload runs there — NAT,
  EC2s, and all tasks stay in the primary AZ.

- **erp-bff is routed at `/erp*`; demo-bff is the ALB default.** Good enough to reach both
  over HTTP. Real erp-bff **browser OIDC** wants host-based routing + a domain so the
  redirect URI resolves at root and `KC_HOSTNAME` matches the issuer (§5.3) — set
  `certificate_arn` + `keycloak_hostname` and switch the erp target to `host_headers`.
  Also: both BFFs carry `spring-boot-starter-actuator`, so the ALB health check hits
  `/actuator/health`. If erp-bff's OAuth2 login chain redirects that path (302 → Keycloak)
  it'll read as unhealthy — permit `/actuator/health` in its security config, or relax the
  `erp-web-ui-bff` target group matcher to `200-399`.

- **Single Kafka broker, RF=1**, carried over from compose — recover-after-restart, not
  failover. Same posture as the local stack; see `docs/messaging.md`.

- **Observability (§1D) is one EC2 running the LGTM stack** (Tempo, Loki, Prometheus,
  Grafana as four containers on a shared docker network), the AWS analogue of the compose
  observability services. All 9 apps get `OTLP_ENDPOINT` (Tempo `:4317`) + `LOKI_URL`
  (Loki `:3100`) + `NORTHWOOD_TRACING_SAMPLING` env, so **traces and logs flow push-based
  out of the box**. Toggle the whole tier with `enable_observability` (the SG rules for it
  already existed). Reach Grafana via SSM port-forward — it has no public IP:
  `terraform output grafana_access_hint`.
  - **Metrics caveat.** §1D metrics are Prometheus **scrape** of `/actuator/prometheus`,
    which doesn't translate to Fargate (no `host.docker.internal`, no stable task IPs). The
    box runs Prometheus with its OTLP receiver enabled and self-scrapes, but service-metric
    collection needs service discovery (ECS SD / Cloud Map) **or** OTLP metrics push from
    the apps — left as a documented follow-up in the staged `prometheus.yml`. The §1D
    Grafana metric panels stay empty on AWS until that's wired; traces + logs are unaffected.
  - **Grafana's Postgres datasource** is templated to the Postgres box's private DNS (the
    compose file hardcodes the `postgres` service name, which doesn't resolve on AWS).

- **Not machine-validated.** Terraform wasn't installed in the authoring environment, so
  `terraform fmt`/`validate`/`plan` have not been run against this yet. Run `terraform
  validate` after `init` before trusting a real apply.
