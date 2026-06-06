output "web_public_ip" {
  description = "Public entry point — open http://<this>/ for the guest front door; the ERP UI (erp-web-ui-bff) is on :8089, Keycloak on :8080."
  value       = module.compute.web_public_ip
}

output "front_door_url" {
  description = "Guest 'start here' page — the first URL to hand a visitor. Links into the ERP UI + Demo Guide."
  value       = module.compute.front_door_url
}

output "keycloak_hostname_hint" {
  description = "The Keycloak issuer host. Defaults to the web box's stable Elastic IP (browser OIDC works out of the box); override with -var keycloak_hostname=<DNS name> for a real domain."
  value       = var.keycloak_hostname != "" ? var.keycloak_hostname : "Defaulting to web Elastic IP ${module.compute.web_public_ip} (browser OIDC works); set -var keycloak_hostname=<Route 53 name> to override."
}

output "ecr_repository_urls" {
  description = "Map app => ECR repo URL. The build script pushes <url>:<image_tag> here before apply."
  value       = module.ecr.repository_urls
}

output "private_ips" {
  description = "Pinned private IPs of the three boxes (web/app/data)."
  value = {
    web  = module.compute.web_private_ip
    app  = module.compute.app_private_ip
    data = module.compute.data_private_ip
  }
}

output "instance_ids" {
  description = "Instance IDs — `aws ssm start-session --target <id>`; stop/start to cut cost."
  value       = module.compute.instance_ids
}

output "grafana_access_hint" {
  description = "Grafana has no public IP — port-forward 3000 over SSM to the data box."
  value       = var.enable_observability ? "aws ssm start-session --target ${module.compute.instance_ids.data} --document-name AWS-StartPortForwardingSession --parameters portNumber=3000,localPortNumber=3000  # then open http://localhost:3000" : "observability disabled"
}

output "artifacts_bucket" {
  description = "Private staging bucket (db init, realm, env files, obs configs)."
  value       = module.compute.artifacts_bucket
}

output "region" {
  value = var.region
}

output "image_tag" {
  value = var.image_tag
}
