variable "name_prefix" {
  type = string
}

variable "service_connect_namespace" {
  description = "Cloud Map HTTP namespace for Service Connect (e.g. \"northwood.local\")."
  type        = string
  default     = "northwood.local"
}

variable "task_secret_arns" {
  description = "Secrets Manager ARNs the execution role may read (secrets[].valueFrom across all tasks)."
  type        = list(string)
  default     = []
}
