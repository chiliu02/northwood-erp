variable "name_prefix" {
  description = "Prefix for all resource names/tags (e.g. \"northwood-demo\")."
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR. /16 leaves room for the three /24 subnets."
  type        = string
  default     = "10.0.0.0/16"
}

variable "az" {
  description = "Single AZ everything is pinned to (demo is single-AZ). Empty => first AZ in the region."
  type        = string
  default     = ""
}

# ---------------------------------------------------------------------------
# Subnets — docs/aws-deployment.html. One public + two private, single AZ:
#   public : the web/auth EC2 (erp-bff + Keycloak), internet-facing
#   app    : the services EC2 (7 services), private
#   infra  : the data EC2 (Postgres + Kafka + observability), private
# The private subnets egress via the NAT instance (see main.tf).
# ---------------------------------------------------------------------------
variable "subnet_cidrs" {
  type = object({
    public = string
    app    = string
    infra  = string
  })
  default = {
    public = "10.0.1.0/24"
    app    = "10.0.2.0/24"
    infra  = "10.0.3.0/24"
  }
}

variable "https_port" {
  description = "Public HTTPS port the Caddy TLS edge listens on (the only operational entry to the web EC2)."
  type        = number
  default     = 443
}

variable "welcome_port" {
  description = "Public HTTP port on the web EC2. Caddy uses it for the Let's Encrypt HTTP-01 challenge and the http->https redirect (named for the legacy front-door port; both default 80)."
  type        = number
  default     = 80
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

variable "telemetry_ports" {
  description = "Observability ingest ports on the data EC2 (OTLP gRPC/HTTP + Loki push)."
  type        = list(number)
  default     = [4317, 4318, 3100]
}

variable "nat_instance_type" {
  description = "NAT instance size — t3.nano is plenty for demo egress."
  type        = string
  default     = "t3.nano"
}

variable "nat_ami_id" {
  description = "AMI for the NAT instance. Empty => latest Amazon Linux 2023 x86_64."
  type        = string
  default     = ""
}
