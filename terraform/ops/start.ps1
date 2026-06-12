#requires -Version 5.1
<#
.SYNOPSIS
  Resume the Northwood AWS demo by STARTING the EC2 instances that stop.ps1
  paused (web + app + data + NAT).

.DESCRIPTION
  The mirror of stop.ps1. Because every container was launched with
  `--restart unless-stopped` and docker is enabled as a systemd service, the
  stack (Postgres, Kafka, the 7 services, BFF, Keycloak, nginx, LGTM) comes back
  on its own once the box boots — no user-data re-run, no DB re-seed. Pinned
  private IPs and the web box Elastic IP are preserved, so the Keycloak issuer
  URL and front-door DNS still resolve to the same place.

  Boot order is not critical (services retry the DB via the restart policy), but
  the data box is started first so Postgres/Kafka are usually up before the app
  and web boxes finish booting.

  Requires AWS CLI v2, authenticated (same account/region as the deployment).

.EXAMPLE
  ./start.ps1
  ./start.ps1 -Region ap-southeast-2 -NamePrefix northwood-demo -Wait
#>
param(
  [string] $Region     = "ap-southeast-2",
  [string] $NamePrefix = "northwood-demo",
  [switch] $Wait        # block until every instance reports 'running'
)

$ErrorActionPreference = "Stop"

# --- preflight -------------------------------------------------------------
aws sts get-caller-identity --output text | Out-Null   # fails fast if AWS CLI unauthenticated

function Get-StoppedIds([string] $tagValue) {
  $out = aws ec2 describe-instances --region $Region `
    --filters "Name=tag:Name,Values=$tagValue" "Name=instance-state-name,Values=stopped,stopping" `
    --query "Reservations[].Instances[].InstanceId" --output text
  if ([string]::IsNullOrWhiteSpace($out)) { return @() }
  return ,($out -split "\s+")
}

Write-Host "==> Finding stopped $NamePrefix-* instances in $Region" -ForegroundColor Cyan

# Data box first (Postgres + Kafka), then everything else.
$dataIds = Get-StoppedIds "$NamePrefix-data"
$restIds = Get-StoppedIds "$NamePrefix-web,$NamePrefix-app,$NamePrefix-nat"
$allIds  = @($dataIds) + @($restIds)

if ($allIds.Count -eq 0) {
  Write-Host "Nothing stopped — already running (or never applied)." -ForegroundColor Yellow
  return
}

if ($dataIds.Count -gt 0) {
  Write-Host "Starting data box: $($dataIds -join ', ')" -ForegroundColor Cyan
  aws ec2 start-instances --region $Region --instance-ids $dataIds --output table
}
if ($restIds.Count -gt 0) {
  Write-Host "Starting web/app/nat: $($restIds -join ', ')" -ForegroundColor Cyan
  aws ec2 start-instances --region $Region --instance-ids $restIds --output table
}

if ($Wait) {
  Write-Host "==> Waiting for 'running' ..." -ForegroundColor Cyan
  aws ec2 wait instance-running --region $Region --instance-ids $allIds
  Write-Host "All instances running. Containers come up via their restart policy (give them a minute)." -ForegroundColor Green
} else {
  Write-Host "Start requested (use -Wait to block until running)." -ForegroundColor Green
}

# Surface the entry point if the deployment state is reachable.
$tfDir = "$PSScriptRoot/../envs/demo"
try {
  $url = terraform -chdir="$tfDir" output -raw front_door_url 2>$null
  if ($? -and -not [string]::IsNullOrWhiteSpace($url)) {
    Write-Host "Front door: $url" -ForegroundColor Green
  }
} catch { }
