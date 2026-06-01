output "web_public_ip" {
  description = "Public entry point — open http://<this>:8089 for erp-web-ui-bff; Keycloak is on :8080."
  value       = module.compute.web_public_ip
}

output "keycloak_hostname_hint" {
  description = "Set -var keycloak_hostname=<web_public_ip or a DNS name> and re-apply so OIDC browser login works (the issuer must be browser-reachable)."
  value       = var.keycloak_hostname != "" ? var.keycloak_hostname : "UNSET — using web private IP; set to ${module.compute.web_public_ip} (or a Route 53 name) and re-apply"
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
