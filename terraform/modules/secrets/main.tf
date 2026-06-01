# ===========================================================================
# Secrets — docs/aws-deployment.html §10. Terraform generates the values
# (so they're never the committed defaults), stores them in Secrets Manager
# for the ECS tasks (task-def secrets[].valueFrom = ARN), and also exposes the
# plaintext (sensitive outputs) so the infra-ec2 module can render the Postgres
# role-login init script + the env files.
#
# Passwords are alphanumeric (special = false) to stay safe inside JDBC URLs
# and SQL string literals.
# ===========================================================================

# ---- generated values ------------------------------------------------------

resource "random_password" "service_db" {
  for_each = toset(var.service_names)
  length   = 24
  special  = false
}

resource "random_password" "postgres_superuser" {
  length  = 24
  special = false
}

resource "random_password" "keycloak_admin" {
  length  = 20
  special = false
}

resource "random_password" "demo_bypass" {
  count   = var.demo_bypass_token == "" ? 1 : 0
  length  = 32
  special = false
}

locals {
  demo_bypass_token = var.demo_bypass_token != "" ? var.demo_bypass_token : random_password.demo_bypass[0].result
}

# ---- Secrets Manager entries -----------------------------------------------

resource "aws_secretsmanager_secret" "service_db" {
  for_each                = toset(var.service_names)
  name                    = "${var.name_prefix}/db/${each.key}"
  recovery_window_in_days = var.recovery_window_in_days
}

resource "aws_secretsmanager_secret_version" "service_db" {
  for_each      = aws_secretsmanager_secret.service_db
  secret_id     = each.value.id
  secret_string = random_password.service_db[each.key].result
}

resource "aws_secretsmanager_secret" "bff_client" {
  name                    = "${var.name_prefix}/keycloak/bff-client-secret"
  recovery_window_in_days = var.recovery_window_in_days
}

resource "aws_secretsmanager_secret_version" "bff_client" {
  secret_id     = aws_secretsmanager_secret.bff_client.id
  secret_string = var.bff_client_secret
}

resource "aws_secretsmanager_secret" "demo_bypass" {
  name                    = "${var.name_prefix}/security/demo-bypass-token"
  recovery_window_in_days = var.recovery_window_in_days
}

resource "aws_secretsmanager_secret_version" "demo_bypass" {
  secret_id     = aws_secretsmanager_secret.demo_bypass.id
  secret_string = local.demo_bypass_token
}

# Stored for operator retrieval / completeness — consumed by the infra EC2s via
# env files, not by ECS tasks.
resource "aws_secretsmanager_secret" "postgres_superuser" {
  name                    = "${var.name_prefix}/db/postgres-superuser"
  recovery_window_in_days = var.recovery_window_in_days
}

resource "aws_secretsmanager_secret_version" "postgres_superuser" {
  secret_id     = aws_secretsmanager_secret.postgres_superuser.id
  secret_string = random_password.postgres_superuser.result
}

resource "aws_secretsmanager_secret" "keycloak_admin" {
  name                    = "${var.name_prefix}/keycloak/admin"
  recovery_window_in_days = var.recovery_window_in_days
}

resource "aws_secretsmanager_secret_version" "keycloak_admin" {
  secret_id     = aws_secretsmanager_secret.keycloak_admin.id
  secret_string = random_password.keycloak_admin.result
}
