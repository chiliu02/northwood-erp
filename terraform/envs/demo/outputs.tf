output "web_public_ip" {
  description = "Stable Elastic IP of the web box (the Caddy TLS edge). The ERP UI + Keycloak hostnames A-record here."
  value       = module.compute.web_public_ip
}

output "front_door_url" {
  description = "Guest 'start here' page (HTTPS via CloudFront) — the first URL to hand a visitor. Links into the ERP UI + Demo Guide."
  value       = var.front_door_domain != "" ? "https://${var.front_door_domain}/" : "(no front_door_domain set)"
}

output "erp_ui_url" {
  description = "Operational ERP SPA — the 'Enter the ERP' target (HTTPS, Let's Encrypt via on-box Caddy)."
  value       = module.compute.erp_url
}

output "keycloak_issuer_url" {
  description = "Keycloak OIDC issuer (HTTPS, Let's Encrypt via on-box Caddy)."
  value       = module.compute.issuer_url
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

output "schedule" {
  description = "Auto start/stop window (EventBridge Scheduler). null when enable_scheduler=false."
  value = var.enable_scheduler ? {
    start    = var.start_cron
    stop     = var.stop_cron
    timezone = var.scheduler_timezone
  } : null
}

output "region" {
  value = var.region
}

output "image_tag" {
  value = var.image_tag
}
