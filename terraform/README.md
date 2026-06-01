# Northwood demo — Terraform

Automates the **docs/aws-deployment.html** topology: a minimal, single-AZ, EC2 +
`docker run` deployment — **no ALB, no ECS/Fargate, no managed NAT Gateway**.
Three EC2 instances across one public + two private subnets, with a small NAT
**instance** for private egress:

| Tier | Subnet | EC2 runs |
|---|---|---|
| web  | public (public IP, `web-sg` :8089 + :8080) | `erp-web-ui-bff` + Keycloak |
| app  | private (`app-sg`) | the 7 services |
| data | private (`infra-sg`) | Postgres + Kafka + (optional) LGTM observability |

The 2 SPAs (S3/CloudFront) and the fully-managed **production** variant
(ECS Fargate / Aurora / MSK / Cognito) are out of scope here — production is
documented in **docs/aws-architecture.html**.

```
terraform/
  bootstrap/                 # one-time: creates the S3 state bucket (local state)
  envs/demo/                 # the root config you apply
    backend.tf               # <- edit: set the state bucket name
    main.tf                  # module wiring + the services map + static private IPs
    variables.tf / outputs.tf / terraform.tfvars.example
  modules/
    network/                 # VPC, 3 subnets, IGW, NAT instance, S3 gateway endpoint, tier SGs
    infra-ec2/               # the 3 EC2 tiers (web/app/data) + docker user-data + IAM + artifacts bucket
    ecr/                     # 8 app repos (7 services + erp-bff)
    secrets/                 # Secrets Manager (DB pwds, BFF client secret, bypass token) + plaintext outputs
  build/                     # build-and-push.{ps1,sh}: buildpack images -> ECR
```

## Prerequisites

- Terraform >= 1.10, AWS CLI v2 (authenticated), Docker daemon, Maven, Java 21.
- An IAM principal with VPC/EC2/ECR/Secrets-Manager/IAM/S3/SSM permissions.
- No ACM cert / domain needed — the demo serves **HTTP** on the web box's public IP.
  (For HTTPS / a stable OIDC redirect, put the production stack in front — see
  `docs/aws-architecture.html`.)

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
cp terraform.tfvars.example terraform.tfvars   # optional: adjust region/tag/etc.

# 4. Create the ECR repos first, so there's somewhere to push the app images.
terraform apply -target=module.ecr

# 5. Build the 8 app images (7 services + erp-bff) and push to ECR.
../../build/build-and-push.ps1 -Tag latest        #  bash: ../../build/build-and-push.sh latest

# 6. Apply everything else (network + NAT, secrets, the 3 EC2s).
terraform apply

# 7. OIDC needs a browser-reachable Keycloak. Grab the web box's public IP and
#    re-apply with it as the issuer hostname (see note below):
terraform output web_public_ip
terraform apply -var="keycloak_hostname=<that-public-ip-or-a-DNS-name>"
```

> **Why the two-phase apply (4 → 5 → 6).** Each EC2's `docker run` user-data pulls
> the app images from ECR on first boot. If the repos are empty, the *instances*
> still come up (so a single `apply` looks like it "succeeded") but the containers
> crash-loop on a pull error. Create the repos, push, then apply the rest. If you
> change app code later: rebuild/push (step 5), then recreate the affected box —
> `terraform apply -replace=module.compute.aws_instance.app` (or `.web`).

> **Keycloak hostname is chicken-and-egg (step 7).** OIDC redirects the browser to
> Keycloak, so `KC_HOSTNAME` / the issuer must be the web box's **public** address —
> which you only know after the first apply. Re-applying with `keycloak_hostname`
> set changes the web box's user-data and therefore **replaces the web EC2**. To
> avoid the re-apply churn, attach an **Elastic IP** to the web box up front and use
> that as `keycloak_hostname`. Until it's set, the issuer falls back to the web
> box's private IP and browser login won't work (the rest of the stack runs fine).

Bring-up order (data → app → web) is handled by Terraform's graph: the boxes use
**pinned static private IPs** (`10.0.1.10 / .2.10 / .3.10`, set in `envs/demo/main.tf`)
so cross-tier URLs (BFF→services, services→Postgres/Kafka/Keycloak) are computed at
plan time with no instance-to-instance dependency cycle. User-data pulls `db/`,
the Keycloak realm, the generated role-login script, and the env files from the
private artifacts bucket on first boot.

## Verify

```powershell
terraform output web_public_ip       # open http://<ip>:8089  (erp-web-ui-bff)  ·  Keycloak on :8080
terraform output instance_ids        # aws ssm start-session --target <data-id>
# on the data box:  docker exec -it northwood-postgres psql -U postgres -d northwood_erp \
#                     -c "select count(*) from sales.customer;"
terraform output grafana_access_hint # SSM port-forward 3000 to the data box, then open localhost:3000
```

## Teardown

```powershell
cd terraform/envs/demo
terraform destroy
# The state bucket (bootstrap) has prevent_destroy = true — delete it by hand if you
# truly want it gone. Stopping the 3 EC2s + the NAT instance is the cheaper "pause"
# (the gp3 root volumes preserve Postgres + Kafka state across stop/start).
```

---

## Design decisions worth knowing

- **NAT *instance*, not a NAT Gateway or VPC endpoints.** The private subnets need
  outbound internet to pull third-party images (Postgres/Kafka from Docker Hub,
  Keycloak from quay.io) and to let the services reach the *public* Keycloak issuer.
  A managed NAT Gateway costs ~$32/mo+; a pure VPC-endpoints route avoids NAT but
  only covers ECR (so Docker Hub/quay would need an ECR pull-through cache + a
  Keycloak issuer/JWKS split). For a throwaway demo we use a **`t3.nano` NAT
  instance** (iptables masquerade, `source_dest_check` off). The free **S3 gateway
  endpoint** still keeps S3 + ECR image-layer traffic off it. Production should use a
  managed NAT Gateway per AZ (`docs/aws-architecture.html`).

- **All app images from ECR; third-party direct.** The build script pushes the 8 app
  images (7 `<svc>-service` + `erp-web-ui-bff`) to ECR; the boxes `docker login` ECR
  via the instance role and pull them. Postgres/Kafka/Keycloak/LGTM are pulled
  straight from their registries through the NAT — no mirroring.

- **Keycloak is public (on the web box).** It must be browser-reachable for the OIDC
  redirect, so it shares the public web EC2 with the BFF. Because the services reach
  that same public issuer through the NAT, **no issuer/JWKS split is needed** — they
  just set `KEYCLOAK_ISSUER_URI` to the public host.

- **Per-service DB login roles (the load-bearing invariant, made real).** The baseline
  `db/northwood_erp.sql` ships the `<svc>_service` roles as `NOLOGIN`. Terraform
  generates a password per service, renders an `ALTER ROLE <svc>_service LOGIN
  PASSWORD …` init script (staged as `03-service-logins.sql`), stages all of them in
  `env/services.env`, and each service container connects as its own role — so the
  schema-per-service least-privilege isolation is genuinely enforced on AWS.

- **State holds secrets → S3 backend.** Terraform sets the Secrets Manager values and
  stages passwords into the artifacts bucket, so state contains them in plaintext;
  that's why state lives in the private + encrypted + versioned bootstrap bucket.

- **Keycloak BFF client secret is fixed, not random.** It must equal the `"secret"`
  baked into `db/keycloak/northwood-realm.json` (`northwood-bff-secret`). Rotating it
  means editing the realm JSON too.

- **Single Kafka broker, RF=1**, carried over from compose — recover-after-restart,
  not failover. Same posture as the local stack; see `docs/messaging.md`.

- **Observability (§1D) is on the data box** (Tempo, Loki, Prometheus, Grafana as
  containers on a shared `northwood` docker network). The app + web boxes get
  `OTLP_ENDPOINT` (Tempo `:4317`) + `LOKI_URL` (Loki `:3100`), so **traces and logs
  flow push-based out of the box**. Toggle the tier with `enable_observability`.
  Reach Grafana via SSM port-forward — it has no public IP
  (`terraform output grafana_access_hint`).
  - **Metrics caveat** (unchanged from the §1D notes): Prometheus *scrape* of
    `/actuator/prometheus` needs service discovery; here it self-scrapes and runs the
    OTLP receiver, so service-metric panels stay empty until SD/OTLP-push is wired —
    traces + logs are unaffected.

- **Machine-validated.** `terraform validate` passes and `terraform fmt` is applied.
  Note that `validate` checks HCL + module wiring + template variables, **not** the
  `docker run` user-data at runtime — a real `apply` is the only way to confirm the
  boxes come up healthy.
```

> **Build script note:** `build/build-and-push.*` was written for the original 9
> deployables. The demo now ships **8** images (demo-web-ui-bff is dropped) — if the
> script still builds/pushes `demo-web-ui-bff`, that extra image is harmless (no repo
> to receive it / simply unused), but you can trim it from the script's app list.
