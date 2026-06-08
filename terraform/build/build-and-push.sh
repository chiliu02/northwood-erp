#!/usr/bin/env bash
# Linux/macOS equivalent of build-and-push.ps1. See that script's header.
# Usage: ./build-and-push.sh [TAG] [TERRAFORM_DIR]
#   SKIP_SPA=1  build images only (skip the erp-web-ui/dist build). When skipped,
#               build erp-web-ui/dist yourself before `terraform apply`, or the
#               web box's nginx (:8090) serves a 404 / stale build.
set -euo pipefail

TAG="${1:-latest}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_DIR="${2:-$SCRIPT_DIR/../envs/demo}"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

docker info >/dev/null   # fail fast if Docker isn't running

echo "==> Reading ECR repository URLs from Terraform ($TF_DIR)"
URLS_JSON="$(terraform -chdir="$TF_DIR" output -json ecr_repository_urls)"
REGION="$(terraform -chdir="$TF_DIR" output -raw region)"
REGISTRY="$(echo "$URLS_JSON" | jq -r 'to_entries[0].value | split("/")[0]')"

echo "==> Logging in to ECR registry $REGISTRY"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"

echo "==> mvn clean install -DskipTests (all modules)"
mvn -f "$REPO_ROOT/pom.xml" clean install -DskipTests

for app in $(echo "$URLS_JSON" | jq -r 'keys[]'); do
  url="$(echo "$URLS_JSON" | jq -r --arg a "$app" '.[$a]')"
  image="${url}:${TAG}"
  echo "==> [$app] building $image"
  mvn -f "$REPO_ROOT/pom.xml" -pl "$app" spring-boot:build-image -DskipTests \
    "-Dspring-boot.build-image.imageName=${image}"
  echo "==> [$app] pushing $image"
  docker push "$image"
done

# Build the operational ERP SPA (erp-web-ui/dist). Not an ECR image: `npm run
# build` produces dist/, which the next `terraform apply` reads as a fileset at
# PLAN time and stages to S3 for the web box's nginx (:8090). Set SKIP_SPA=1 to
# skip (then build dist/ yourself before apply).
if [[ "${SKIP_SPA:-0}" == "1" ]]; then
  echo "==> Skipping SPA build (SKIP_SPA=1). Build erp-web-ui/dist manually before apply."
else
  command -v npm >/dev/null || { echo "npm not found — install Node.js or set SKIP_SPA=1 (then build erp-web-ui/dist manually before apply)." >&2; exit 1; }
  echo "==> Building operational ERP SPA (npm ci && npm run build) in $REPO_ROOT/erp-web-ui"
  ( cd "$REPO_ROOT/erp-web-ui" && npm ci && npm run build )
fi

echo "==> Done. Pushed images at tag '$TAG'$([[ "${SKIP_SPA:-0}" == "1" ]] || echo ' + built erp-web-ui/dist'). Now: terraform -chdir=\"$TF_DIR\" apply"
