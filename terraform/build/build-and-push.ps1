#requires -Version 5.1
<#
.SYNOPSIS
  Build the 9 Northwood deployables as container images via Spring Boot
  Buildpacks (no Dockerfiles) and push them to ECR.

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
  [string[]] $Apps  # optional subset; default = all 9
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path "$PSScriptRoot/../.."

# --- preflight -------------------------------------------------------------
docker info | Out-Null   # fails fast if the Docker daemon isn't running

Write-Host "==> Reading ECR repository URLs from Terraform ($TerraformDir)" -ForegroundColor Cyan
$urlsJson = terraform -chdir="$TerraformDir" output -json ecr_repository_urls
if (-not $?) { throw "terraform output failed — run 'terraform apply -target=module.ecr' first." }
$urls   = $urlsJson | ConvertFrom-Json
$region = (terraform -chdir="$TerraformDir" output -raw region)

# Registry host is the part before the first '/'.
$anyUrl   = ($urls.PSObject.Properties | Select-Object -First 1).Value
$registry = $anyUrl.Split("/")[0]

Write-Host "==> Logging in to ECR registry $registry" -ForegroundColor Cyan
aws ecr get-login-password --region $region | docker login --username AWS --password-stdin $registry
if (-not $?) { throw "ECR docker login failed." }

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
  docker push $image
  if (-not $?) { throw "docker push failed for $app." }
}

Write-Host "==> Done. Pushed $($targets.Count) image(s) at tag '$Tag'." -ForegroundColor Cyan
Write-Host "    Now run: terraform -chdir=`"$TerraformDir`" apply" -ForegroundColor Cyan
