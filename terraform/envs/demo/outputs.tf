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
