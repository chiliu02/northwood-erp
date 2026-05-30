output "repository_urls" {
  description = "Map app name => repository URL (the push target + task-def image base)."
  value       = { for k, r in aws_ecr_repository.this : k => r.repository_url }
}

output "repository_arns" {
  description = "Map app name => repository ARN (for the ECS execution-role pull policy)."
  value       = { for k, r in aws_ecr_repository.this : k => r.arn }
}
