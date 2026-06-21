<#
.SYNOPSIS
  Reset the load-test database to a clean, seeded, ready-to-run state.

.DESCRIPTION
  The concurrent load tests share ONE Postgres (docs/concurrent-load-test.md §2) —
  shared state is the whole point, it is where contention lives. The price of that
  shared substrate: a test that intentionally leaves orders in a non-terminal state
  contaminates the next test's post-run InvariantVerifier, whose convergence check
  (invariant 1) polls for EVERY saga in the database to reach a terminal state and
  cannot tell a leftover from a fresh order.

  The clearest offender is ConcurrentRaceProbesTest: its probes drive orders through
  ship / cancel / partial-ship but DELIBERATELY never pay them, so their fulfilment
  sagas correctly sit at `wait_for_completion` forever. Run the probes before a
  Gatling o2c run and that run's verifier fails with a census of the probe orders —
  a false negative that has nothing to do with the system under test.

  Rather than mandate a run ORDER, make every test order-independent: reset the data
  to a clean slate BEFORE each test. This script does exactly that, and leaves the
  database in the "ready to run any scenario" state (the README §"Prerequisites"
  stock bumps applied), so the very next `gatling:test` / `mvn test` starts clean.

  HOW: recreate the Postgres data volume so the baseline init script
  (config/postgresql/northwood_erp.sql) + the demo seed
  (config/postgresql/northwood_erp_seed.sql, layered via docker-compose.seed.yml)
  both re-run from scratch — the same guaranteed-clean path as
  `docker compose down -v` but scoped to Postgres only. Kafka, Keycloak, the
  observability tier, AND the five host Spring services are left running; the
  services' HikariCP pools auto-reconnect to the fresh database (their committed
  Kafka offsets mean no prior event is redelivered). Verified: a 200-user o2c run
  is fully green immediately after this reset with no service restart.

  Takes ~15-20s. Run it between EVERY load test for order-independent runs:

      ./reset-data.ps1   # clean slate
      mvn -Pload-test -pl load-test test -Dtest=ConcurrentRaceProbesTest
      ./reset-data.ps1   # clean slate again
      mvn -Pload-test -pl load-test gatling:test -Dgatling.simulationClass=...

.PARAMETER ComposeProject
  Docker Compose project name (defaults to the repo dir basename, lowercased —
  the name Compose derives the `<project>_northwood-pgdata` volume from).

.EXAMPLE
  ./reset-data.ps1
#>
param(
    [string]$ComposeProject = "northwood",
    # Compose SERVICE key (used by `docker compose rm/up`) vs the container_name
    # (used by `docker inspect`/`docker exec`) — they differ and are not interchangeable.
    [string]$Service = "postgres",
    [string]$Container = "northwood-postgres",
    [int]$HealthTimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"

# Run from the repo root regardless of where the script is invoked, so the
# docker-compose*.yml paths resolve.
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    Write-Host "[reset] recreating Postgres data volume (baseline + seed re-run)..."
    # Stop+remove the container FIRST so the named volume is no longer in use.
    docker compose rm -s -f $Service | Out-Null

    # The named volume is `<project>_northwood-pgdata`. Resolve it defensively in
    # case the project name differs from the default.
    $vol = docker volume ls --format "{{.Name}}" | Where-Object { $_ -like "*northwood-pgdata" } | Select-Object -First 1
    if ($vol) {
        docker volume rm $vol | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Failed to remove volume $vol (still in use?) — reset aborted to avoid a false 'clean' state" }
        Write-Host "[reset] removed volume $vol"
    } else {
        Write-Host "[reset] no northwood-pgdata volume found — a fresh init will run anyway"
    }

    docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d $Service | Out-Null

    Write-Host "[reset] waiting for Postgres healthy..."
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    do {
        Start-Sleep -Seconds 3
        $health = docker inspect --format "{{.State.Health.Status}}" $Container 2>$null
    } while ($health -ne "healthy" -and (Get-Date) -lt $deadline)
    if ($health -ne "healthy") { throw "Postgres did not become healthy within ${HealthTimeoutSeconds}s (status: $health)" }

    # Prerequisite stock bumps (README §Prerequisites). One bump serves BOTH scenarios:
    #   * default run (products.csv): ample on_hand for the to_stock finished goods so
    #     reservation succeeds at placement (rules out reorder-point noise);
    #   * to_order run (to-order-products.csv): the make-to-order chest tree's nested
    #     work orders never hit a raw-material shortage — so bump every RM-* high.
    # FG-CHEST-001 + FG-CARPET-001 are LEFT at the seed's zero on purpose: their zero
    # stock is what triggers the to_order shortage path the supply-side run exercises.
    Write-Host "[reset] applying prerequisite stock bumps..."
    $mainWarehouse = "00000000-0000-7000-8000-000000000020"
    $bumpFg = "UPDATE inventory.stock_balance SET on_hand_quantity=100000 WHERE warehouse_id='$mainWarehouse' AND product_id IN ('00000000-0000-7000-8000-000000000400','00000000-0000-7000-8000-000000000001','00000000-0000-7000-8000-000000000200');"
    $bumpRm = "UPDATE inventory.stock_balance SET on_hand_quantity=100000 WHERE warehouse_id='$mainWarehouse' AND product_id IN (SELECT product_id FROM product.product WHERE sku LIKE 'RM-%');"
    docker exec -i $Container psql -U postgres -d northwood_erp -v ON_ERROR_STOP=1 -c $bumpFg -c $bumpRm | Out-Null

    $orders = docker exec -i $Container psql -U postgres -d northwood_erp -tA -c "SELECT count(*) FROM sales.sales_order_header;"
    Write-Host "[reset] done — database clean ($($orders.Trim()) sales orders), stock bumped, services will auto-reconnect."
}
finally {
    Pop-Location
}
