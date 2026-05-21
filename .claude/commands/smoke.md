---
description: Fresh-volume reset of docker-compose infra + wait for Postgres ready
---

Wipe the docker-compose volumes and bring everything back up cleanly. Used after any change to `db/northwood_erp.sql` or `db/northwood_erp_seed.sql`, when seed data needs resetting, or when Liquibase bookkeeping in the `databasechangelog` table has drifted from what's on disk.

Pick the variant that matches what the user asked for:

| Variant | Command | Postgres comes up with |
|---|---|---|
| Schema only (default) | `docker compose up -d` | Schemas, roles, grants — but no rows. Use when the test/demo wants to populate via events from zero. |
| Schema + seed | `docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d` | Same baseline plus the showcase fixtures (products, BOMs, customers, suppliers, GL chart, etc.). Use when you need a populated database to demo against immediately. |

If the user didn't specify, default to schema + seed — that's the demo-from-zero flow most slices expect.

Steps:

1. Run `docker compose down -v` (wipes Postgres data, Kafka topics, Keycloak realm state — anything on a named volume goes).
2. Run the variant command from the table above.
3. Poll until the **postgres** service reports `(healthy)` — typically ~10s. Use the unfiltered command `docker compose ps` (or filter by **service** name, e.g. `docker compose ps postgres`) — **not** the container name `northwood-postgres`. `docker compose ps <NAME>` expects the service name from `docker-compose.yml` (one of: `postgres`, `kafka`, `keycloak`), and silently returns `no such service` if you give it the container name.

   Example poll loop (PowerShell):
   ```powershell
   $deadline = (Get-Date).AddSeconds(60)
   do {
       Start-Sleep -Seconds 2
       $status = (docker compose ps --format "{{.Status}}" postgres)
       if ($status -match "healthy") { break }
   } while ((Get-Date) -lt $deadline)
   docker compose ps
   ```
4. Report which containers came up and any that failed (look for `Exit` or `Restarting` in `ps` output).
5. Keycloak takes a further ~30-60s before its health probe flips to `healthy` (it's importing `db/keycloak/northwood-realm.json` on first boot). Postgres being healthy is the gate for starting backend services; Keycloak being healthy is the gate for the BFFs' OIDC code flow. Kafka typically reports healthy in step 3 already.

What the smoke covers:
- Postgres runs `db/northwood_erp.sql` on first boot of the fresh volume (mounted into `/docker-entrypoint-initdb.d/01-…`) — every schema, role, grant, partition, and PL/pgSQL function. With the seed override layered in, it also runs `db/northwood_erp_seed.sql` immediately after (`02-…`) for the demo fixture rows.
- Keycloak imports `db/keycloak/northwood-realm.json` on first boot — 13 roles + 13 demo users.
- Kafka starts with `auto.create.topics.enable=true` so service-side outbox publishes will create their topics on first send.

After the smoke completes, running services that were pointed at the old volume need a restart — their JDBC pools hold dead connections.

Reference: `docs/demo-script.md` § "Bringing the stack up" has the full nine-terminal walkthrough; this command covers infra reset only, not service launches (use `/run-svc` for those).
