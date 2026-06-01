output "vpc_id" {
  value = aws_vpc.this.id
}

output "az" {
  description = "The single AZ everything is pinned to."
  value       = local.az
}

output "subnet_ids" {
  description = "Subnet IDs by tier (public / app / infra)."
  value = {
    public = aws_subnet.public.id
    app    = aws_subnet.app.id
    infra  = aws_subnet.infra.id
  }
}

output "security_group_ids" {
  description = "Security group IDs by tier (web / app / infra / endpoints)."
  value       = { for k, sg in aws_security_group.this : k => sg.id }
}
