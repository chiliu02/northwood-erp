# Northwood demo — Terraform

Automates the **docs/aws-deployment.html** topology: a minimal, single-AZ, EC2 +
`docker run` deployment — **no ALB, no ECS/Fargate, no managed NAT Gateway**.
Three EC2 instances across one public + two private subnets, with a small NAT
**instance** for private egress:

| Tier | Subnet | EC2 runs |
|---|---|---|
| web  | public (public IP, `web-sg` :80 + :8090 + :8089 + :8080) | guest **front door** (nginx :80) + **operational ERP SPA** (nginx :8090) + `erp-web-ui-bff` (:8089) + Keycloak (:8080) |
| app  | private (`app-sg`) | the 7 services |
| data | private (`infra-sg`) | Postgres + Kafka + (optional) LGTM observability |

The operational `erp-web-ui` SPA is served **from the web box** by an nginx on
`:8090` that reverse-proxies `/api`, `/oauth2`, `/login`, `/logout` to the BFF
(same-origin, so the SPA's relative calls + the OIDC code flow work without
CORS). Hosting the SPAs on **S3/CloudFront**, and the fully-managed
**production** variant (ECS Fargate / Aurora / MSK / Cognito), are out of scope
here — production is documented in **docs/aws-architecture.html**.

```
terraform/
  bootstrap/                 # one-time: creates the S3 state bucket (local state)
  envs/demo/                 # the root config you apply
    backend.tf               # <- edit: set the state bucket name
    main.tf                  # module wiring + the services map + static private IPs
    schedule.tf              # EventBridge Scheduler: weekday auto start/stop (cost saver)
    variables.tf / outputs.tf / terraform.tfvars.example
  modules/
    network/                 # VPC, 3 subnets, IGW, NAT instance, S3 gateway endpoint, tier SGs
    infra-ec2/               # the 3 EC2 tiers (web/app/data) + web Elastic IP + docker user-data + IAM + artifacts bucket
    ecr/                     # 8 app repos (7 services + erp-bff)
    secrets/                 # Secrets Manager (DB pwds, BFF client secret, bypass token) + plaintext outputs
  build/                     # build-and-push.{ps1,sh}: buildpack images -> ECR
  ops/                       # start.ps1 / stop.ps1: pause/resume the fleet by hand
```

## Prerequisites

- Terraform >= 1.10, AWS CLI v2 (authenticated), Docker daemon, Maven, Java 21.
- An IAM principal with VPC/EC2/ECR/Secrets-Manager/IAM/S3/SSM permissions.
- No ACM cert / domain needed — the demo serves **HTTP** on the web box's public IP.
  (For HTTPS / a stable OIDC redirect, put the production stack in front — see
  `docs/aws-architecture.html`.)

## Bring-up

> Steps 1–4 are **one-time setup** (state bucket, backend wiring, `init` + tfvars,
> ECR repos) — skip them on later runs. The repeat-on-change loop is **5 / 6**:
> rebuild & push images + the SPA (one script call), then `apply` (add
> `-replace=…app`/`…web` to force a box to re-pull on boot). Re-run `terraform init`
> only if providers/modules change; re-run step 4 only after a `terraform destroy`
> (which drops the ECR repos).

```powershell
# 1. State backend (local-state bootstrap). The bucket name defaults to
#    northwood-tfstate-chiliu02 (matching envs/demo/backend.tf). For your own
#    deployment pick a globally-unique name:
#    terraform apply -var="state_bucket_name=northwood-tfstate-<your-account-id>"
cd terraform/bootstrap
terraform init
terraform apply

# 2. Point the real config at that bucket.
#    Already set to northwood-tfstate-chiliu02 in terraform/envs/demo/backend.tf.
#    Only if you overrode the name in step 1: set bucket = "<that name>" there too
#    (+ region if not Sydney) — backend.tf can't read the var, so the two must match.

# 3. Init the demo env.
cd ../envs/demo
terraform init
cp terraform.tfvars.example terraform.tfvars   # optional: adjust region/tag/etc.

# 4. Create the ECR repos first, so there's somewhere to push the app images.
#    Quote the flag in PowerShell: it otherwise splits the bare `-target=module.ecr`
#    token and errors `Invalid target "module"`. (Same for any `-replace=…` flag
#    below; bash doesn't need the quotes.)
terraform apply "-target=module.ecr"

# 5. Build the 8 app images (7 services + erp-bff) + the operational ERP SPA, and
#    push the images to ECR. One run produces every artifact step 6 needs: the
#    script also does `npm ci && npm run build` in erp-web-ui/, so a fresh dist/ is
#    on disk for the apply — Terraform reads that fileset at PLAN time and stages it
#    to S3 for the web box's nginx (:8090). Images-only iteration: pass -SkipSpa
#    (PowerShell) / SKIP_SPA=1 (bash), then build erp-web-ui/dist yourself before
#    step 6, else :8090 serves a 404 / stale build a later build won't fix without
#    re-applying.
../../build/build-and-push.ps1 -Tag latest        #  bash: ../../build/build-and-push.sh latest

# 6. Apply everything else (network + NAT, secrets, the 3 EC2s + the web Elastic IP
#    + the SPA staged to S3).
terraform apply

# 7. Done — browser OIDC works out of the box: the web box has a stable Elastic IP
#    and Keycloak's issuer defaults to it. Grab the entry points:
terraform output front_door_url   # guest "start here" page
terraform output web_public_ip    # the stable Elastic IP (also the issuer host)
#    Optional — to front it with a real domain instead, point DNS at the EIP and
#    re-apply (user-data only re-runs on replacement, so force-replace the boxes):
#    terraform apply -var="keycloak_hostname=<your-dns-name>" \
#      -replace=module.compute.aws_instance.web -replace=module.compute.aws_instance.app
```

> **Why the two-phase apply (4 → 5 → 6) — first bring-up only.** Each EC2's
> `docker run` user-data pulls the app images from ECR on first boot. If the repos
> are empty, the *instances* still come up (so a single `apply` looks like it
> "succeeded") but the containers crash-loop on a pull error. So the **first**
> bring-up splits it: step 4 creates the repos, step 5 pushes images into them,
> step 6 applies the rest. The split is a one-time bootstrap — the repos persist.
>
> **Shipping a new app version (deployment still up): just steps 5 → 6.** Skip
> 1–4 entirely. Step 5 rebuilds & pushes the images (and the SPA) to the existing
> repos; step 6 redeploys. Because user-data only re-runs on instance
> *replacement*, a plain `apply` won't pull the new image onto a running box — so
> recreate the affected box(es):
> `terraform apply -replace=module.compute.aws_instance.app` (or `.web`, or both).

> **Keycloak issuer rides the web box's Elastic IP.** OIDC redirects the browser to
> Keycloak, so `KC_HOSTNAME` / the issuer must be the web box's **public** address.
> The module allocates a stable **Elastic IP** for the web box and defaults the
> issuer to it, so browser login works on the **first** apply — no second re-apply,
> and the address survives instance replacement / stop-start. The front door's
> **"Enter the ERP"** link reuses the same address (→ the operational SPA on `:8090`).
> To front it with a real domain, point DNS at the EIP and re-apply with
> `-var="keycloak_hostname=<your-dns-name>"`. Because `user_data_replace_on_change`
> is false, that change is in-place and won't re-run user-data on the live boxes —
> add `-replace=module.compute.aws_instance.web -replace=module.compute.aws_instance.app`
> to force the recreation that re-bakes the issuer.

Bring-up order (data → app → web) is handled by Terraform's graph: the boxes use
**pinned static private IPs** (`10.0.1.10 / .2.10 / .3.10`, set in `envs/demo/main.tf`)
so cross-tier URLs (BFF→services, services→Postgres/Kafka/Keycloak) are computed at
plan time with no instance-to-instance dependency cycle. User-data pulls `db/`,
the Keycloak realm, the generated role-login script, the env files, and the built
SPA (`spa/`) from the private artifacts bucket on first boot.

## Verify

```powershell
terraform output front_door_url        # open this first — the guest "start here" page (http://<ip>/)
terraform output front_door_domain_url # same front door via DNS once it propagates (A record -> web EIP, e.g. http://www.northwood.chiliu02.com/); empty front_door_domain => use front_door_url
terraform output web_public_ip       # operational ERP UI on http://<ip>:8090  ·  Keycloak on :8080  (BFF :8089 is proxied by the SPA nginx)
terraform output instance_ids        # aws ssm start-session --target <data-id>
# on the data box:  docker exec -it northwood-postgres psql -U postgres -d northwood_erp \
#                     -c "select count(*) from sales.customer;"
terraform output grafana_access_hint # SSM port-forward 3000 to the data box, then open localhost:3000
```

## Pause / resume (cost saver)

`terraform destroy` is for tearing the demo **down**; to just stop paying for it
overnight or on weekends, **stop the EC2 fleet instead of destroying it**.
Stopping keeps the ECR images, the gp3 root volumes (so Postgres + Kafka data
survive), the pinned private IPs, and the web box's Elastic IP — so the Keycloak
issuer URL and front-door DNS stay valid, and the resume is fast with **no DB
re-seed** (every container runs `--restart unless-stopped` on a docker systemd
service, so it comes back on boot). While stopped you pay only for EBS + the
Elastic IP (~$10/mo) instead of the running fleet. ECR itself is ~$0.10/GB-mo, so
keeping the images costs almost nothing — it's a *time* saver (no rebuild/push),
not a cost.

This is out-of-band from Terraform — `aws_instance` has no power-state, so
`terraform plan` shows **no drift** after a stop or start.

### By hand

```powershell
./terraform/ops/stop.ps1            # stop web + app + data + NAT (fire-and-forget)
./terraform/ops/start.ps1 -Wait     # start them again, block until running
```

Both discover the four boxes by the `northwood-demo-*` Name tag (so the NAT box,
which isn't in the `instance_ids` output, is paused too) and take
`-Region` / `-NamePrefix` / `-Wait`.

### On a schedule (Terraform — `schedule.tf`)

`enable_scheduler` (default **true**) creates two **EventBridge Scheduler**
schedules that call the EC2 API directly (no Lambda) to start/stop the same four
boxes on a weekday business-hours window:

| | Default | Variable |
|---|---|---|
| Start | 08:30 Mon–Fri | `start_cron` = `cron(30 8 ? * MON-FRI *)` |
| Stop  | 17:00 Mon–Fri | `stop_cron`  = `cron(0 17 ? * MON-FRI *)` |
| Timezone | `Australia/Sydney` (DST-aware) | `scheduler_timezone` |

`MON-FRI` means **the fleet stays stopped all weekend** — it stops Friday 17:00
and doesn't start again until Monday 08:30. The schedules assume a least-privilege
role scoped to `ec2:Start/StopInstances` on `northwood-demo-*`-tagged instances.

```powershell
terraform apply                                     # create/update the schedules
terraform output schedule                           # show the active window
terraform apply -var="start_cron=cron(0 9 ? * MON-FRI *)"   # e.g. start 09:00 instead
terraform apply -var="enable_scheduler=false"       # remove the schedules (instances untouched)
```

> **The schedule only acts at the cron times.** Applying it mid-window doesn't
> stop a running fleet immediately — use `ops/stop.ps1` for that. Likewise the
> first auto-start is the next 08:30 on a weekday.

## Teardown

```powershell
cd terraform/envs/demo
terraform destroy
# The state bucket (bootstrap) has prevent_destroy = true — delete it by hand if you
# truly want it gone. To merely PAUSE (keep data + images, cut cost), stop the fleet
# instead — see "Pause / resume" above.
```

---

## Design decisions worth knowing

- **The operational SPA is served from the web box by nginx (not S3/CloudFront).**
  `erp-web-ui` is a Vite SPA that calls the BFF with **relative** paths (`/api`,
  `/oauth2`, `/login`, `/logout`), so it must be served same-origin as the BFF. On
  this minimal demo we skip S3/CloudFront and instead run an nginx on the web box
  (`:8090`) that serves the built `dist/` and reverse-proxies those four prefixes to
  the BFF (`:8089`). The BFF runs with `server.forward-headers-strategy=framework`,
  so the `X-Forwarded-*` headers nginx sends let Spring build the OIDC `redirect_uri`
  / `{baseUrl}` against the public `:8090` origin. The realm's `localhost:8089` +
  `localhost:5174` redirect URIs / web origins are rewritten to `<public-host>:8090`
  at import time (host-side `sed` in the web user-data). `dist/` is staged to S3 by
  Terraform (`aws_s3_object.spa`, a `fileset` over `erp-web-ui/dist`), so **build the
  SPA before `apply`** — step 5 does this for you (`-SkipSpa` to opt out). Production
  serves the SPA from S3/CloudFront (`docs/aws-architecture.html`).

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
  `config/postgresql/northwood_erp.sql` ships the `<svc>_service` roles as `NOLOGIN`. Terraform
  generates a password per service, renders an `ALTER ROLE <svc>_service LOGIN
  PASSWORD …` init script (staged as `03-service-logins.sql`), stages all of them in
  `env/services.env`, and each service container connects as its own role — so the
  schema-per-service least-privilege isolation is genuinely enforced on AWS.

- **State holds secrets → S3 backend.** Terraform sets the Secrets Manager values and
  stages passwords into the artifacts bucket, so state contains them in plaintext;
  that's why state lives in the private + encrypted + versioned bootstrap bucket.

- **Keycloak BFF client secret is fixed, not random.** It must equal the `"secret"`
  baked into `config/keycloak/northwood-realm.json` (`northwood-bff-secret`). Rotating it
  means editing the realm JSON too.

- **Single Kafka broker, RF=1**, carried over from compose — recover-after-restart,
  not failover. Same posture as the local stack; see `docs/messaging.md`.

- **Observability is on the data box** (Tempo, Loki, Prometheus, Grafana as
  containers on a shared `northwood` docker network). The app + web boxes get
  `OTLP_ENDPOINT` (Tempo `:4317`) + `LOKI_URL` (Loki `:3100`), so **traces and logs
  flow push-based out of the box**. Toggle the tier with `enable_observability`.
  Reach Grafana via SSM port-forward — it has no public IP
  (`terraform output grafana_access_hint`).
  - **Metrics caveat:** Prometheus *scrape* of
    `/actuator/prometheus` needs service discovery; here it self-scrapes and runs the
    OTLP receiver, so service-metric panels stay empty until SD/OTLP-push is wired —
    traces + logs are unaffected.

- **Machine-validated.** `terraform validate` passes and `terraform fmt` is applied.
  Note that `validate` checks HCL + module wiring + template variables, **not** the
  `docker run` user-data at runtime — a real `apply` is the only way to confirm the
  boxes come up healthy.
```

> **Build script note:** `build/build-and-push.*` builds the **8** app images
> (the 7 services + `erp-web-ui-bff`) by reading the ECR repo list from
> `terraform output`, so it always stays in sync with the `module.ecr` repos.
