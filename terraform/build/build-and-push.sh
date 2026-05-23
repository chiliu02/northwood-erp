#!/usr/bin/env bash
# Linux/macOS equivalent of build-and-push.ps1. See that script's header.
# Usage: ./build-and-push.sh [TAG] [TERRAFORM_DIR]
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

echo "==> Done. Pushed images at tag '$TAG'. Now: terraform -chdir=\"$TF_DIR\" apply"
