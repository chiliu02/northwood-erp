#requires -Version 5.1
<#
.SYNOPSIS
  Pause the Northwood AWS demo by STOPPING its EC2 instances (web + app + data +
  NAT) — without destroying anything.

.DESCRIPTION
  Stopping (vs `terraform destroy`) keeps everything that makes the next start
  fast and cheap-to-resume:
    * ECR images, VPC/subnets/SGs, Secrets — untouched.
    * Postgres/Kafka data + the on-box container images on each EBS volume.
    * The pinned private IPs and the web box's Elastic IP — so Keycloak's issuer
      URL and the front-door DNS record stay valid across the pause.
  All containers run with `--restart unless-stopped` and docker is a systemd
  service, so on the matching start.ps1 the stack comes back up on its own.

  While stopped you pay only for EBS + the Elastic IP (~$10/mo) instead of the
  running fleet. This is out-of-band from Terraform: `aws_instance` has no
  power-state, so `terraform plan` still shows no drift afterwards.

  Requires AWS CLI v2, authenticated (same account/region as the deployment).

.EXAMPLE
  ./stop.ps1
  ./stop.ps1 -Region ap-southeast-2 -NamePrefix northwood-demo -Wait
#>
param(
  [string] $Region     = "ap-southeast-2",
  [string] $NamePrefix = "northwood-demo",
  [switch] $Wait        # block until every instance reports 'stopped'
)

$ErrorActionPreference = "Stop"

# --- preflight -------------------------------------------------------------
aws sts get-caller-identity --output text | Out-Null   # fails fast if AWS CLI unauthenticated

Write-Host "==> Finding running $NamePrefix-* instances in $Region" -ForegroundColor Cyan
$ids = aws ec2 describe-instances --region $Region `
  --filters "Name=tag:Name,Values=$NamePrefix-*" "Name=instance-state-name,Values=running,pending" `
  --query "Reservations[].Instances[].InstanceId" --output text

if ([string]::IsNullOrWhiteSpace($ids)) {
  Write-Host "Nothing running — already stopped (or never applied)." -ForegroundColor Yellow
  return
}

$idList = $ids -split "\s+"
Write-Host "Stopping: $($idList -join ', ')" -ForegroundColor Cyan
aws ec2 stop-instances --region $Region --instance-ids $idList --output table

if ($Wait) {
  Write-Host "==> Waiting for 'stopped' ..." -ForegroundColor Cyan
  aws ec2 wait instance-stopped --region $Region --instance-ids $idList
  Write-Host "All instances stopped." -ForegroundColor Green
} else {
  Write-Host "Stop requested (use -Wait to block until fully stopped)." -ForegroundColor Green
}
