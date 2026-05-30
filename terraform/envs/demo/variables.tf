variable "region" {
  type    = string
  default = "ap-southeast-2"
}

variable "name_prefix" {
  description = "Prefix for resource names + ECR repos + Service Connect."
  type        = string
  default     = "northwood-demo"
}

variable "image_tag" {
  description = "Tag the build script pushed to ECR for every app (e.g. \"latest\" or a git SHA)."
  type        = string
  default     = "latest"
}

variable "repo_root" {
  description = "Repo root for staging db/ files. Empty => derived as three dirs up from envs/demo."
  type        = string
  default     = ""
}

variable "load_seed_data" {
  description = "Load db/northwood_erp_seed.sql (populated demo). False => empty schema."
  type        = bool
  default     = true
}

variable "certificate_arn" {
  description = "ACM cert ARN for HTTPS on the ALB. Empty => HTTP-only (throwaway demo)."
  type        = string
  default     = ""
}

variable "keycloak_hostname" {
  description = "Public Keycloak URL (KC_HOSTNAME) for OIDC browser login. Empty => internal-only."
  type        = string
  default     = ""
}

# ---- secrets knobs ---------------------------------------------------------

variable "demo_bypass_token" {
  description = "Demo-bypass token. Empty => random. Set to \"\" secret value via the module to disable."
  type        = string
  default     = ""
  sensitive   = true
}

variable "bff_client_secret" {
  description = "Keycloak northwood-bff client secret — must match the realm JSON."
  type        = string
  default     = "northwood-bff-secret"
  sensitive   = true
}

# ---- sizing ----------------------------------------------------------------

variable "service_cpu" {
  type    = number
  default = 512
}

variable "service_memory" {
  type    = number
  default = 1024
}

variable "bff_cpu" {
  type    = number
  default = 512
}

variable "bff_memory" {
  type    = number
  default = 1024
}

variable "service_desired_counts" {
  description = "Per-service task count overrides, keyed by schema (e.g. { sales = 2 }). Default 1."
  type        = map(number)
  default     = {}
}

variable "bff_desired_counts" {
  description = "Per-BFF task count overrides (e.g. { \"demo-web-ui-bff\" = 2 }). Default 1."
  type        = map(number)
  default     = {}
}

variable "infra_instance_types" {
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

# ---- observability (§1D) ---------------------------------------------------

variable "enable_observability" {
  description = "Provision the LGTM observability EC2 (Loki/Grafana/Tempo + Prometheus) and point all 9 apps at it (OTLP traces + Loki logs). False => no obs box, no telemetry env (apps keep localhost defaults; nothing receives)."
  type        = bool
  default     = true
}

variable "observability_instance_type" {
  description = "Instance type for the observability box (the LGTM stack is heavier than a single infra box)."
  type        = string
  default     = "t3.medium"
}

variable "tracing_sampling" {
  description = "Trace sampling probability stamped into NORTHWOOD_TRACING_SAMPLING (1.0 = every request, demo default; 0.1 ≈ prod-style)."
  type        = number
  default     = 1.0
}
