variable "region" {
  type    = string
  default = "ap-southeast-2"
}

variable "name_prefix" {
  description = "Prefix for resource names + ECR repos."
  type        = string
  default     = "northwood-demo"
}

variable "image_tag" {
  description = "Tag the build script pushed to ECR for the 8 app images (e.g. \"latest\" or a git SHA)."
  type        = string
  default     = "latest"
}

variable "repo_root" {
  description = "Repo root for staging db/ + realm + obs files. Empty => derived three dirs up from envs/demo."
  type        = string
  default     = ""
}

variable "load_seed_data" {
  description = "Load db/northwood_erp_seed.sql (populated demo). False => empty schema."
  type        = bool
  default     = true
}

variable "keycloak_hostname" {
  description = "Public address (DNS or the web EC2's public IP) used as Keycloak's KC_HOSTNAME / OIDC issuer. Empty => falls back to the web box's private IP (browser OIDC login won't work until set — set it after first apply once the public IP is known, or use an Elastic IP)."
  type        = string
  default     = ""
}

variable "enable_observability" {
  description = "Run Tempo/Loki/Prometheus/Grafana on the data box + point the apps at it (OTLP traces + Loki logs)."
  type        = bool
  default     = true
}

# ---- secrets knobs ---------------------------------------------------------

variable "demo_bypass_token" {
  description = "Carried for the shared SecurityConfig bean; the demo BFF is gone so this is effectively unused (set \"\" to disable the bypass filter on the services)."
  type        = string
  default     = ""
  sensitive   = true
}

variable "bff_client_secret" {
  description = "Keycloak northwood-bff client secret — must match db/keycloak/northwood-realm.json."
  type        = string
  default     = "northwood-bff-secret"
  sensitive   = true
}
