output "web_public_ip" {
  description = "Stable Elastic IP of the web EC2 — the Caddy TLS edge (:443/:80). The ui_hostname + auth_hostname A-records resolve here; survives instance replacement."
  value       = aws_eip.web.public_ip
}

output "erp_url" {
  description = "Operational ERP SPA origin (https://<ui_hostname>) the front-door 'Enter the ERP' link targets. Used to render the front-door page too."
  value       = local.erp_url
}

output "issuer_url" {
  description = "Keycloak OIDC issuer (https://<auth_hostname>/realms/northwood)."
  value       = local.kc_issuer
}

output "grafana_url" {
  description = "Public, read-only Grafana URL (https://<grafana_hostname>) when published via Caddy; empty when not exposed."
  value       = local.grafana_host != "" ? "https://${local.grafana_host}" : ""
}

output "web_private_ip" {
  value = aws_instance.web.private_ip
}

output "app_private_ip" {
  value = aws_instance.app.private_ip
}

output "data_private_ip" {
  value = aws_instance.data.private_ip
}

output "artifacts_bucket" {
  description = "Private staging bucket (db init, realm, env files, obs configs)."
  value       = aws_s3_bucket.artifacts.id
}

output "instance_ids" {
  description = "Instance IDs (for `aws ssm start-session` + stop/start to save cost)."
  value = {
    web  = aws_instance.web.id
    app  = aws_instance.app.id
    data = aws_instance.data.id
  }
}
