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
  description = "Load config/postgresql/northwood_erp_seed.sql (populated demo). False => empty schema."
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

# ---- front-door DNS (see dns.tf) -------------------------------------------

variable "dns_zone_name" {
  description = "Existing Route 53 public hosted zone the front-door record is created in (no trailing dot)."
  type        = string
  default     = "chiliu02.com"
}

variable "front_door_domain" {
  description = "FQDN A-record'd to the web box Elastic IP for the guest front door (HTTP only). Empty => no record (reach the front door by IP). Does NOT affect the ERP UI / Keycloak issuer, which stay on the IP."
  type        = string
  default     = "www.northwood.chiliu02.com"
}

# ---- cost-saver schedule (see schedule.tf) ---------------------------------

variable "enable_scheduler" {
  description = "Create EventBridge Scheduler rules that auto-start/stop the EC2 fleet on a weekday business-hours window (cuts idle cost). The instances themselves are unaffected by toggling this off — only the schedules are removed."
  type        = bool
  default     = true
}

variable "scheduler_timezone" {
  description = "IANA timezone the start/stop cron expressions are evaluated in (handles DST automatically)."
  type        = string
  default     = "Australia/Sydney"
}

variable "start_cron" {
  description = "EventBridge Scheduler cron for the daily START. Default: 08:30, Mon-Fri (no weekend start)."
  type        = string
  default     = "cron(30 8 ? * MON-FRI *)"
}

variable "stop_cron" {
  description = "EventBridge Scheduler cron for the daily STOP. Default: 17:00, Mon-Fri."
  type        = string
  default     = "cron(0 17 ? * MON-FRI *)"
}

# ---- secrets knobs ---------------------------------------------------------

variable "bff_client_secret" {
  description = "Keycloak northwood-bff client secret — must match config/keycloak/northwood-realm.json."
  type        = string
  default     = "northwood-bff-secret"
  sensitive   = true
}
