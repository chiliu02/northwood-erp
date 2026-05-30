variable "name_prefix" {
  type = string
}

variable "subnet_id" {
  description = "Infra subnet ID (all three boxes share it)."
  type        = string
}

variable "security_group_ids" {
  description = "Per-box SG IDs from the network module. `obs` is consumed only when enable_observability = true."
  type = object({
    postgres = string
    kafka    = string
    keycloak = string
    obs      = optional(string, "")
  })
}

variable "enable_observability" {
  description = "Provision the LGTM observability EC2 (Loki/Grafana/Tempo + Prometheus). False => no obs box (apps fall back to their localhost telemetry defaults; nothing receives)."
  type        = bool
  default     = true
}

variable "observability_instance_type" {
  description = "Instance type for the observability box. The LGTM stack is heavier than a single infra box, so it defaults larger."
  type        = string
  default     = "t3.medium"
}

variable "observability_images" {
  description = "Container images for the LGTM stack (versions carried over from docker-compose.yml)."
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
    postgres = string
    kafka    = string
    keycloak = string
  })
  default = {
    postgres = "t3.small"
    kafka    = "t3.small"
    keycloak = "t3.small"
  }
}

variable "data_volume_size_gb" {
  description = "gp3 data-volume size for the Postgres + Kafka data dirs."
  type        = number
  default     = 20
}

variable "ami_id" {
  description = "AMI ID. Empty => latest Amazon Linux 2023 x86_64 via SSM public parameter."
  type        = string
  default     = ""
}

variable "images" {
  description = "Container images (carried over from docker-compose.yml)."
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

variable "kafka_cluster_id" {
  description = "KRaft cluster ID — same fixed value as the compose so a recreated box reattaches to a kept volume."
  type        = string
  default     = "4L6g3nShT-eMCtK--X86sw"
}

variable "repo_root" {
  description = "Absolute path to the repo root, for staging db/ files to S3 (db/northwood_erp.sql, the seed, the Keycloak realm)."
  type        = string
}

variable "load_seed_data" {
  description = "Stage db/northwood_erp_seed.sql as init-script 02 (populated demo). False => empty schema."
  type        = bool
  default     = true
}

variable "postgres_superuser_password" {
  description = "POSTGRES_PASSWORD for the superuser. Distributed to the box via a private-bucket env file, not user-data."
  type        = string
  sensitive   = true
}

variable "keycloak_admin_password" {
  description = "KC_BOOTSTRAP_ADMIN_PASSWORD."
  type        = string
  sensitive   = true
}

variable "service_db_passwords" {
  description = <<-EOT
    Per-service login passwords keyed by service name (product, sales, inventory,
    manufacturing, purchasing, finance, reporting). Terraform renders these into an
    `ALTER ROLE <svc>_service LOGIN PASSWORD ...` init script so each service
    authenticates as its own role (the schema-per-service least-privilege invariant).
  EOT
  type        = map(string)
  sensitive   = true
}

variable "keycloak_hostname" {
  description = "Public URL clients use to reach Keycloak (KC_HOSTNAME). Empty => internal-only (set before exercising OIDC browser login)."
  type        = string
  default     = ""
}
