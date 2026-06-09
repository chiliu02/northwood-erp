variable "name_prefix" {
  type = string
}

variable "service_names" {
  description = "Services that get their own DB role + password (the 7 backend services)."
  type        = list(string)
}

variable "bff_client_secret" {
  description = <<-EOT
    Keycloak confidential-client secret for northwood-bff. MUST match the
    `"secret"` baked into config/keycloak/northwood-realm.json (default
    "northwood-bff-secret"). Rotating it means editing the realm JSON too —
    that's why it's a fixed variable, not a random_password.
  EOT
  type        = string
  default     = "northwood-bff-secret"
  sensitive   = true
}

variable "recovery_window_in_days" {
  description = "Secrets Manager deletion recovery window. 0 = immediate (demo-friendly: destroy/recreate won't trip name-in-use)."
  type        = number
  default     = 0
}
