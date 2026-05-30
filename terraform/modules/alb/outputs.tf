output "alb_dns_name" {
  description = "Public DNS of the ALB — the demo's entry point."
  value       = aws_lb.this.dns_name
}

output "alb_zone_id" {
  description = "ALB hosted-zone ID (for a Route 53 alias record)."
  value       = aws_lb.this.zone_id
}

output "target_group_arns" {
  description = "Map target name => target group ARN (passed to the BFF ecs-service modules)."
  value       = { for k, tg in aws_lb_target_group.this : k => tg.arn }
}

output "listener_arn" {
  description = "Primary listener ARN — BFF services should depend on this so the TG is LB-associated before registration."
  value       = local.https_enabled ? aws_lb_listener.https[0].arn : aws_lb_listener.http.arn
}
