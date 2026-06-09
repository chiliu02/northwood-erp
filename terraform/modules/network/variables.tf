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

variable "bff_port" {
  description = "erp-web-ui-bff port, exposed to the internet on the public EC2."
  type        = number
  default     = 8089
}

variable "keycloak_port" {
  description = "Keycloak port — public (browser OIDC redirect) + reachable from the app tier for JWKS."
  type        = number
  default     = 8080
}

variable "welcome_port" {
  description = "Guest front-door (static welcome page) port — public on the web EC2. Keep in sync with the infra-ec2 module's welcome_port (both default 80)."
  type        = number
  default     = 80
}

variable "ui_port" {
  description = "Operational ERP SPA port — public on the web EC2. An nginx on this port serves the built erp-web-ui and reverse-proxies /api,/oauth2,/login,/logout to the BFF. Keep in sync with the infra-ec2 module's ui_port (both default 8090)."
  type        = number
  default     = 8090
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
