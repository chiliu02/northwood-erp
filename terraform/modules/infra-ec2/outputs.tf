output "web_public_ip" {
  description = "Public IP of the web EC2 (front door :80, erp-bff :8089, Keycloak :8080). Use for KEYCLOAK hostname + browsing the demo."
  value       = aws_instance.web.public_ip
}

output "front_door_url" {
  description = "Guest front-door (start-here) page — the first thing to open."
  value       = "http://${aws_instance.web.public_ip}${var.welcome_port == 80 ? "" : ":${var.welcome_port}"}/"
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
