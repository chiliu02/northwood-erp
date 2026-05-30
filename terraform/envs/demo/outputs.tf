output "alb_dns_name" {
  description = "Public entry point. http://<this>/ = demo-bff; http://<this>/erp = erp-bff."
  value       = module.alb.alb_dns_name
}

output "ecr_repository_urls" {
  description = "Map app => ECR repo URL. The build script pushes <url>:<image_tag> here."
  value       = module.ecr.repository_urls
}

output "ecs_cluster_name" {
  value = module.ecs_cluster.cluster_name
}

output "infra_private_dns" {
  description = "Private DNS of the three infra boxes (for psql/kafka CLI via SSM, and sanity checks)."
  value = {
    postgres = module.infra_ec2.postgres_private_dns
    kafka    = module.infra_ec2.kafka_private_dns
    keycloak = module.infra_ec2.keycloak_private_dns
  }
}

output "infra_instance_ids" {
  description = "Instance IDs — `aws ssm start-session --target <id>`, and stop/start to cut cost (§12)."
  value       = module.infra_ec2.instance_ids
}

output "observability_private_dns" {
  description = "Private DNS of the observability box (OTLP :4317 / Loki :3100). Empty when enable_observability = false."
  value       = module.infra_ec2.observability_private_dns
}

output "grafana_access_hint" {
  description = "How to reach Grafana — it has no public IP, so port-forward 3000 over SSM."
  value       = var.enable_observability ? "aws ssm start-session --target ${lookup(module.infra_ec2.instance_ids, "observability", "")} --document-name AWS-StartPortForwardingSession --parameters portNumber=3000,localPortNumber=3000  # then open http://localhost:3000" : "observability disabled"
}

output "artifacts_bucket" {
  description = "Private staging bucket (db/ init scripts + env files)."
  value       = module.infra_ec2.artifacts_bucket
}

output "region" {
  value = var.region
}

output "image_tag" {
  value = var.image_tag
}
