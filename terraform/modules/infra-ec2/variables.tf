variable "name_prefix" {
  type = string
}

variable "subnet_ids" {
  description = "Subnet IDs by tier from the network module."
  type = object({
    public = string
    app    = string
    infra  = string
  })
}

variable "security_group_ids" {
  description = "SG IDs by tier from the network module."
  type = object({
    web   = string
    app   = string
    infra = string
  })
}

variable "private_ips" {
  description = "Pinned static private IPs per EC2 (web/app/infra) — used to wire cross-tier URLs at plan time."
  type = object({
    web   = string
    app   = string
    infra = string
  })
}

# ---- ECR (all images are pulled from ECR over the VPC endpoints) -----------
variable "ecr_registry" {
  description = "ECR registry host, e.g. <acct>.dkr.ecr.<region>.amazonaws.com."
  type        = string
}

variable "ecr_repository_arns" {
  description = "ARNs of every ECR repo the boxes pull from (app + mirrored third-party) — for the instance pull policy."
  type        = list(string)
}

variable "image_prefix" {
  description = "ECR repo namespace prefix (= name_prefix). Full ref: <registry>/<image_prefix>/<repo>:<tag>."
  type        = string
}

variable "image_tag" {
  description = "Tag for the 8 app images (erp-bff + 7 services)."
  type        = string
  default     = "latest"
}

variable "services" {
  description = "Service name => listen port. Repo per service is <name>-service."
  type        = map(number)
}

variable "bff_name" {
  description = "The single BFF deployable name (erp-web-ui-bff)."
  type        = string
  default     = "erp-web-ui-bff"
}

variable "bff_port" {
  type    = number
  default = 8089
}

# ---- third-party images pulled straight from their registries (via the NAT) -
variable "images" {
  description = "Third-party images (Docker Hub / quay.io) — pulled directly through the NAT instance."
  type = object({
    postgres = string
    kafka    = string
    keycloak = string
  })
  default = {
    postgres = "postgres:17"
    kafka    = "apache/kafka:4.1.2"
    keycloak = "quay.io/keycloak/keycloak:26.0"
  }
}

variable "observability_images" {
  description = "LGTM images (Docker Hub) — pulled directly through the NAT instance."
  type = object({
    tempo      = string
    loki       = string
    prometheus = string
    grafana    = string
  })
  default = {
    tempo      = "grafana/tempo:2.7.0"
    loki       = "grafana/loki:3.3.0"
    prometheus = "prom/prometheus:v3.0.1"
    grafana    = "grafana/grafana:11.3.1"
  }
}

variable "instance_types" {
  type = object({
    web  = string
    app  = string
    data = string
  })
  default = {
    web  = "t3.small"
    app  = "t3.medium" # 7 service containers
    data = "t3.medium" # postgres + kafka + LGTM
  }
}

variable "data_volume_size_gb" {
  description = "Extra gp3 capacity for Postgres + Kafka data dirs on the data box."
  type        = number
  default     = 20
}

variable "ami_id" {
  description = "AMI ID. Empty => latest Amazon Linux 2023 x86_64 via SSM public parameter."
  type        = string
  default     = ""
}

variable "kafka_cluster_id" {
  description = "KRaft cluster ID — fixed so a recreated box reattaches to a kept volume."
  type        = string
  default     = "4L6g3nShT-eMCtK--X86sw"
}

variable "repo_root" {
  description = "Absolute path to the repo root, for staging db/ + realm + obs configs to S3."
  type        = string
}

variable "load_seed_data" {
  description = "Stage db/northwood_erp_seed.sql as init-script 02 (populated demo)."
  type        = bool
  default     = true
}

variable "enable_observability" {
  description = "Run Tempo/Loki/Prometheus/Grafana on the data box; wire OTLP/Loki env on the app + web boxes."
  type        = bool
  default     = true
}

variable "keycloak_hostname" {
  description = "Public hostname/IP clients use to reach Keycloak (the web EC2's public address) — the OIDC issuer. Empty => falls back to the web box private IP (browser login won't work until set)."
  type        = string
  default     = ""
}

variable "postgres_superuser_password" {
  type      = string
  sensitive = true
}

variable "keycloak_admin_password" {
  type      = string
  sensitive = true
}

variable "bff_client_secret" {
  type      = string
  sensitive = true
}

variable "service_db_passwords" {
  description = "Per-service login passwords keyed by service name."
  type        = map(string)
  sensitive   = true
}
