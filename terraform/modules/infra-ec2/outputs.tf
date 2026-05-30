output "postgres_private_dns" {
  description = "Private DNS of the Postgres box — feed into <SERVICE>_DB_URL."
  value       = aws_instance.postgres.private_dns
}

output "kafka_private_dns" {
  description = "Private DNS of the Kafka box — feed into KAFKA_BOOTSTRAP_SERVERS (append :9092)."
  value       = aws_instance.kafka.private_dns
}

output "keycloak_private_dns" {
  description = "Private DNS of the Keycloak box — internal issuer base (append :8080/realms/northwood)."
  value       = aws_instance.keycloak.private_dns
}

output "observability_private_dns" {
  description = "Private DNS of the observability box — feed into OTLP_ENDPOINT (:4317) and LOKI_URL (:3100). Empty when enable_observability = false."
  value       = var.enable_observability ? aws_instance.observability[0].private_dns : ""
}

output "artifacts_bucket" {
  description = "Private staging bucket holding db/ init scripts + env files."
  value       = aws_s3_bucket.artifacts.id
}

output "instance_ids" {
  description = "Instance IDs (for `aws ssm start-session` and stop/start to save cost)."
  value = merge(
    {
      postgres = aws_instance.postgres.id
      kafka    = aws_instance.kafka.id
      keycloak = aws_instance.keycloak.id
    },
    var.enable_observability ? { observability = aws_instance.observability[0].id } : {},
  )
}
