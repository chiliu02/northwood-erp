variable "name_prefix" {
  description = "Prefix for all resource names/tags (e.g. \"northwood-demo\")."
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR. /16 leaves room for the four /24 subnets."
  type        = string
  default     = "10.0.0.0/16"
}

variable "az" {
  description = "Primary AZ — web/app/infra tiers, NAT, and all instances live here. Empty => first AZ in the region."
  type        = string
  default     = ""
}

variable "az_b" {
  description = <<-EOT
    Secondary AZ used ONLY for a second public subnet, because an internet-facing
    ALB requires >=2 AZs. No workload runs here — the demo stays effectively
    single-AZ. Empty => second AZ in the region.
  EOT
  type        = string
  default     = ""
}

variable "subnet_cidrs" {
  description = "Per-tier subnet CIDRs (matches docs/aws-demo-deployment.md §4; public_b added for the ALB's 2-AZ requirement)."
  type = object({
    public   = string
    public_b = string
    web      = string
    app      = string
    infra    = string
  })
  default = {
    public   = "10.0.1.0/24"
    public_b = "10.0.4.0/24"
    web      = "10.0.2.0/24"
    app      = "10.0.3.0/24"
    infra    = "10.0.11.0/24"
  }
}

variable "app_port_range" {
  description = "Inclusive TCP port range the 7 services listen on (8081-8087)."
  type = object({
    from = number
    to   = number
  })
  default = {
    from = 8081
    to   = 8087
  }
}

variable "bff_ports" {
  description = "The two BFF ports exposed to the ALB (demo-bff 8080, erp-bff 8089)."
  type        = list(number)
  default     = [8080, 8089]
}
