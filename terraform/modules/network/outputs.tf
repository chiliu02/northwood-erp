output "vpc_id" {
  value = aws_vpc.this.id
}

output "az" {
  description = "The single AZ everything is pinned to."
  value       = local.az
}

output "subnet_ids" {
  description = "Subnet IDs by tier."
  value = {
    public   = aws_subnet.public.id
    public_b = aws_subnet.public_b.id
    web      = aws_subnet.web.id
    app      = aws_subnet.app.id
    infra    = aws_subnet.infra.id
  }
}

output "alb_subnet_ids" {
  description = "The two public subnets the ALB spans (its 2-AZ requirement)."
  value       = [aws_subnet.public.id, aws_subnet.public_b.id]
}

output "security_group_ids" {
  description = "Security group IDs by tier (alb/web/app/postgres/kafka/keycloak/obs)."
  value       = { for k, sg in aws_security_group.this : k => sg.id }
}
