<#
.SYNOPSIS
  Provision the Keycloak realm for the concurrent load test (docs/concurrent-load-test.md §5).

.DESCRIPTION
  The committed demo realm (config/keycloak/northwood-realm.json) has 13 single-role
  named users and NO direct-grant client — neither shape can drive the load test, which
  needs (a) many distinct identities (distinct created_by) and (b) a password-grant client
  to mint one bearer token per virtual user.

  This script idempotently adds, via the Keycloak Admin REST API:
    * a public, direct-access-grant client `northwood-loadtest` in the `northwood` realm;
    * N users `user-0 … user-{N-1}`, password `password`, each carrying the order-to-cash
      role bundle so ONE virtual user can drive the whole flow (place → ship → pay):
      sales_clerk + warehouse_clerk + accountant, plus production_planner so the
      supply-side OperationsDriver (§5.6 — goods receipt + work-order completion) can
      run as a load user too.

  Re-running is safe: existing client/users/role-mappings are detected and skipped.
  This is the operational equivalent of the `Bootstrap` step named in the design doc §7,
  done in PowerShell so it is directly verifiable on the Windows showcase host.

.PARAMETER Users
  How many load users to provision (default 50).

.EXAMPLE
  ./provision-keycloak.ps1 -Users 50
#>
param(
    [int]$Users = 50,
    [string]$KeycloakBase = "http://localhost:8090",
    [string]$Realm = "northwood",
    [string]$AdminUser = "admin",
    [string]$AdminPassword = "admin",
    [string]$ClientId = "northwood-loadtest",
    [string]$UserPassword = "password",
    [string[]]$Roles = @("sales_clerk", "warehouse_clerk", "accountant", "production_planner")
)

$ErrorActionPreference = "Stop"

function Get-AdminToken {
    $body = @{
        grant_type = "password"; client_id = "admin-cli"
        username   = $AdminUser;  password = $AdminPassword
    }
    $resp = Invoke-RestMethod -Method Post -ContentType "application/x-www-form-urlencoded" `
        -Uri "$KeycloakBase/realms/master/protocol/openid-connect/token" -Body $body
    return $resp.access_token
}

$token = Get-AdminToken
$headers = @{ Authorization = "Bearer $token"; "Content-Type" = "application/json" }
$admin = "$KeycloakBase/admin/realms/$Realm"

# --- 1. Ensure the direct-grant client -------------------------------------
$existing = Invoke-RestMethod -Headers $headers -Uri "$admin/clients?clientId=$ClientId"
if ($existing.Count -eq 0) {
    $clientRep = @{
        clientId                  = $ClientId
        name                      = "Northwood Load Test (direct grant)"
        enabled                   = $true
        publicClient              = $true
        protocol                  = "openid-connect"
        standardFlowEnabled       = $false
        directAccessGrantsEnabled = $true
        serviceAccountsEnabled    = $false
        fullScopeAllowed          = $true
    } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Headers $headers -Uri "$admin/clients" -Body $clientRep | Out-Null
    Write-Host "Created client $ClientId"
} else {
    Write-Host "Client $ClientId already exists — skipping"
}

# --- 2. Resolve the realm-role representations (needed for role-mapping) ----
$roleReps = @()
foreach ($r in $Roles) {
    $roleReps += Invoke-RestMethod -Headers $headers -Uri "$admin/roles/$r"
}
# Force a JSON array even if a single role (PS 5.1 has no -AsArray).
$roleRepsJson = "[" + (($roleReps | ForEach-Object { $_ | ConvertTo-Json -Depth 5 -Compress }) -join ",") + "]"

# --- 3. Ensure the N load users --------------------------------------------
$created = 0; $skipped = 0
for ($i = 0; $i -lt $Users; $i++) {
    $username = "user-$i"
    $found = Invoke-RestMethod -Headers $headers -Uri "$admin/users?username=$username&exact=true"
    if ($found.Count -gt 0) { $skipped++; continue }

    $userRep = @{
        username      = $username
        enabled       = $true
        firstName     = "Load"
        lastName      = "User$i"
        email         = "$username@loadtest.local"
        emailVerified = $true
        credentials   = @(@{ type = "password"; value = $UserPassword; temporary = $false })
    } | ConvertTo-Json -Depth 5
    Invoke-RestMethod -Method Post -Headers $headers -Uri "$admin/users" -Body $userRep | Out-Null

    $u = Invoke-RestMethod -Headers $headers -Uri "$admin/users?username=$username&exact=true"
    $userId = $u[0].id
    Invoke-RestMethod -Method Post -Headers $headers -Uri "$admin/users/$userId/role-mappings/realm" -Body $roleRepsJson | Out-Null
    $created++
}

Write-Host "Users: $created created, $skipped already present (roles: $($Roles -join ', '))"
Write-Host "Done. Mint a token with:"
Write-Host "  client_id=$ClientId  username=user-0  password=$UserPassword  grant_type=password"
