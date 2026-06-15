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

# ---- public hostnames (HTTPS — see frontdoor.tf / dns.tf) ------------------
# All three are sub-domains of dns_zone_name, A-record'd to the web box Elastic
# IP (ui/auth) or the front-door CloudFront distribution (welcome). A public CA
# can't issue a cert for a bare IP, so the operational surfaces need real names.

variable "ui_hostname" {
  description = "FQDN for the operational ERP SPA. The on-box Caddy terminates TLS here (Let's Encrypt) and reverse-proxies the SPA nginx. This is the 'Enter the ERP' target and the OIDC redirect/origin host."
  type        = string
  default     = "app.northwood.chiliu02.com"
}

variable "auth_hostname" {
  description = "FQDN for Keycloak — the OIDC issuer (https://<this>/realms/northwood). Caddy terminates TLS here and proxies to Keycloak."
  type        = string
  default     = "auth.northwood.chiliu02.com"
}

variable "acme_email" {
  description = "Contact email Caddy registers with Let's Encrypt (expiry/renewal notices). Empty => Caddy registers anonymously (certs still issue)."
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
  description = "FQDN for the always-on guest front door, served over HTTPS by CloudFront in front of the S3 welcome bucket (frontdoor.tf). Empty => no CloudFront/record (reach the front door only via the operational hosts)."
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
