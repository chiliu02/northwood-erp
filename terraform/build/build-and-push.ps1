#requires -Version 5.1
<#
.SYNOPSIS
  Build the 8 Northwood deployables (7 services + erp-web-ui-bff) as container
  images via Spring Boot Buildpacks (no Dockerfiles) and push them to ECR.

.DESCRIPTION
  Reads the ECR repository URLs from `terraform output` (so the ECR repos must
  exist first: `terraform apply -target=module.ecr`), logs in to ECR, builds
  each app tagged directly as <ecr-repo-url>:<tag>, and pushes.

  Requires: a running Docker daemon, Maven, AWS CLI v2 (authenticated), and a
  Terraform state that already contains module.ecr.

.EXAMPLE
  ./build-and-push.ps1 -Tag latest
  ./build-and-push.ps1 -Tag (git rev-parse --short HEAD) -Apps sales-service,inventory-service
#>
param(
  [string]   $Tag = "latest",
  [string]   $TerraformDir = "$PSScriptRoot/../envs/demo",
  [string[]] $Apps  # optional subset; default = all 8
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path "$PSScriptRoot/../.."

# --- preflight -------------------------------------------------------------
docker info | Out-Null   # fails fast if the Docker daemon isn't running

Write-Host "==> Reading ECR repository URLs from Terraform ($TerraformDir)" -ForegroundColor Cyan
$urlsJson = terraform -chdir="$TerraformDir" output -json ecr_repository_urls
if (-not $?) { throw "terraform output failed — run 'terraform apply -target=module.ecr' first." }
$urls   = $urlsJson | ConvertFrom-Json

# Registry host is the part before the first '/'.
$anyUrl   = ($urls.PSObject.Properties | Select-Object -First 1).Value
$registry = $anyUrl.Split("/")[0]

# Region is encoded in the registry host (<account>.dkr.ecr.<region>.amazonaws.com).
# Derive it from there rather than a separate `terraform output region`, which a
# `-target=module.ecr` apply prunes from state (this script runs in exactly that phase).
$region = $registry.Split(".")[3]

# Authenticate to ECR via an isolated docker config dir instead of `docker login`.
# Workaround for a Docker Desktop bug (observed on 29.4.3): `docker login` to ECR fails
# with "400 Bad Request" on the daemon's /v2/ verify even though the credential is valid
# and the daemon's push path works fine. Writing the auth into a throwaway config dir and
# pointing `docker push --config` at it sidesteps the broken login flow and leaves the
# user's global ~/.docker/config.json (and its credsStore) untouched.
Write-Host "==> Authenticating to ECR registry $registry (isolated docker config)" -ForegroundColor Cyan
$token = aws ecr get-login-password --region $region
if (-not $? -or [string]::IsNullOrWhiteSpace($token)) { throw "aws ecr get-login-password failed — check AWS auth." }
$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("AWS:$token"))

$dockerConfigDir = Join-Path ([System.IO.Path]::GetTempPath()) "northwood-ecr-docker-config"
New-Item -ItemType Directory -Force -Path $dockerConfigDir | Out-Null
$authJson = @{ auths = @{ $registry = @{ auth = $basic } } } | ConvertTo-Json -Depth 5
[System.IO.File]::WriteAllText((Join-Path $dockerConfigDir "config.json"), $authJson, [System.Text.UTF8Encoding]::new($false))

# --- build all module jars once (services depend on shared + *-events) -----
Write-Host "==> mvn clean install -DskipTests (all modules)" -ForegroundColor Cyan
mvn -f "$repoRoot/pom.xml" clean install -DskipTests
if (-not $?) { throw "Maven build failed." }

# --- build-image + push per app -------------------------------------------
$targets = if ($Apps) { $Apps } else { $urls.PSObject.Properties.Name }

foreach ($app in $targets) {
  $url = $urls.$app
  if (-not $url) { throw "No ECR repo URL for '$app' (known: $($urls.PSObject.Properties.Name -join ', '))." }
  $image = "${url}:$Tag"

  Write-Host "==> [$app] building image $image" -ForegroundColor Green
  mvn -f "$repoRoot/pom.xml" -pl $app spring-boot:build-image -DskipTests `
    "-Dspring-boot.build-image.imageName=$image"
  if (-not $?) { throw "build-image failed for $app." }

  Write-Host "==> [$app] pushing $image" -ForegroundColor Green
  docker --config $dockerConfigDir push $image
  if (-not $?) { throw "docker push failed for $app." }
}

Remove-Item $dockerConfigDir -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "==> Done. Pushed $($targets.Count) image(s) at tag '$Tag'." -ForegroundColor Cyan
Write-Host "    Now run: terraform -chdir=`"$TerraformDir`" apply" -ForegroundColor Cyan
