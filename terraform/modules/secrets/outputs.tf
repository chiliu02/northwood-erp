# ---- ARNs for ECS task-def secrets[] (the app reads these at runtime) -------

output "service_db_password_arns" {
  description = "Map service => Secrets Manager ARN of its DB password."
  value       = { for k, s in aws_secretsmanager_secret.service_db : k => s.arn }
}

output "bff_client_secret_arn" {
  value = aws_secretsmanager_secret.bff_client.arn
}

output "demo_bypass_token_arn" {
  value = aws_secretsmanager_secret.demo_bypass.arn
}

output "all_task_secret_arns" {
  description = "Every ARN an ECS execution role must be allowed to read (for the pull policy)."
  value = concat(
    [for s in aws_secretsmanager_secret.service_db : s.arn],
    [aws_secretsmanager_secret.bff_client.arn, aws_secretsmanager_secret.demo_bypass.arn],
  )
}

# ---- plaintext (sensitive) for the infra-ec2 module -------------------------

output "service_db_passwords" {
  description = "Map service => plaintext DB password (rendered into the Postgres role-login init script)."
  value       = { for k, p in random_password.service_db : k => p.result }
  sensitive   = true
}

output "postgres_superuser_password" {
  value     = random_password.postgres_superuser.result
  sensitive = true
}

output "keycloak_admin_password" {
  value     = random_password.keycloak_admin.result
  sensitive = true
}
